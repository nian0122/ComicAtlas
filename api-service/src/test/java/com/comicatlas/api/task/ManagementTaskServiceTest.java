package com.comicatlas.api.task.service;

import com.comicatlas.api.task.assembler.TaskResponseAssembler;
import com.comicatlas.api.exporter.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.api.importer.persistence.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import com.comicatlas.api.task.service.impl.ManagementTaskServiceImpl;
import com.comicatlas.api.metadata.policy.MetadataRefreshTaskPolicy;
import com.comicatlas.api.task.service.impl.TaskAggregationServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 管理任务服务重试链路单测（仅覆盖 IMPORT 类型重试入队的防御分支，
 * 完整统一任务重试行为由 ManagementTaskServiceIT 集成验证）。
 */
@ExtendWith(MockitoExtension.class)
class ManagementTaskServiceTest {

    @Mock private ManagementTaskMapper taskMapper;
    @Mock private ManagementTaskItemMapper itemMapper;
    @Mock private ComicMapper comicMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private ExportTaskMapper exportTaskMapper;
    @Mock private OutboxService outboxService;
    @Mock private ImportTaskMapper importTaskMapper;
    @Mock private ImportRetryCoordinator importRetryCoordinator;
    @Mock private TaskRetryPublisher taskRetryPublisher;
    @Mock private TaskResponseAssembler taskResponseAssembler;
    @Mock private TaskQueryService taskQueryService;
    @Mock private TaskInternalQueryService taskInternalQueryService;
    @Spy
    @InjectMocks
    private MetadataRefreshTaskPolicy metadataRefreshTaskPolicy;
    @Spy
    @InjectMocks
    private TaskAggregationServiceImpl taskAggregationService;

    @InjectMocks
    private ManagementTaskServiceImpl service;

    /** 纯 mock 环境无 MyBatis 容器，需预注册 TableInfo 供 LambdaUpdateWrapper 解析列名。 */
    @BeforeAll
    static void initMybatisTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ManagementTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ManagementTaskItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Chapter.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Comic.class);
    }

    @BeforeEach
    void injectAggregationService() {
        ReflectionTestUtils.setField(service, "taskAggregationService", taskAggregationService);
        ReflectionTestUtils.setField(service, "taskLifecyclePolicy", metadataRefreshTaskPolicy);
    }

    @Test
    void retryTask_importTaskInconsistentState_throwsConflict() {
        ManagementTask task = new ManagementTask();
        task.setId(99L);
        task.setTaskType(TaskType.IMPORT);
        task.setStatus(ManagementTaskStatus.FAILED);
        task.setAttempt(1);
        when(taskMapper.selectById(99L)).thenReturn(task);

        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(1L);
        item.setTaskId(99L);
        item.setTargetType("COMIC");
        item.setTargetId(10L);
        item.setOperationType(TaskType.IMPORT);
        item.setStatus(ManagementTaskStatus.FAILED);
        when(itemMapper.selectByTaskId(99L)).thenReturn(List.of(item));

        // 导入任务非终态且非 PENDING：说明与管理任务状态不一致，重试入队应抛冲突回滚而非静默卡死
        org.mockito.Mockito.doThrow(new BusinessException(409, "导入任务非终态且未被重置"))
                .when(taskRetryPublisher).publish(eq(99L), eq(item), eq(2));

        assertThrows(BusinessException.class, () -> service.retryTask(99L));
    }

    @Test
    void createMetadataRefreshTask_同漫画多章节只占用一次漫画状态() {
        CreateManagementTaskRequest request = new CreateManagementTaskRequest();
        request.setTaskType(TaskType.METADATA_REFRESH);
        request.setOperation("刷新元数据");
        request.setTargetType("COMIC");
        request.setTargets(List.of(chapterTarget(11L), chapterTarget(12L)));
        Chapter firstChapter = new Chapter();
        firstChapter.setId(11L);
        firstChapter.setComicId(1L);
        Chapter secondChapter = new Chapter();
        secondChapter.setId(12L);
        secondChapter.setComicId(1L);
        when(chapterMapper.selectBatchIds(any())).thenReturn(List.of(firstChapter, secondChapter));
        when(comicMapper.lockForMetadataRefresh(1L)).thenReturn(1);
        when(itemMapper.countByLockKey(anyString())).thenReturn(0L);

        service.createTask(request, null, "{}");

        verify(itemMapper, times(2)).insert(any(ManagementTaskItem.class));
    }

    private static CreateManagementTaskRequest.TaskTarget chapterTarget(Long chapterId) {
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("CHAPTER");
        target.setTargetId(chapterId);
        target.setOperationType(TaskType.METADATA_REFRESH);
        return target;
    }

    @Test
    void retryTask_recoveryTask_throwsConflict() {
        ManagementTask task = new ManagementTask();
        task.setId(100L);
        task.setTaskType(TaskType.RECOVERY);
        task.setStatus(ManagementTaskStatus.FAILED);
        when(taskMapper.selectById(100L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(100L));
        verify(itemMapper, never()).selectByTaskId(any());
    }

    @Test
    void retryTask_scanTask_throwsConflict() {
        ManagementTask task = new ManagementTask();
        task.setId(101L);
        task.setTaskType(TaskType.DIRECTORY_SCAN);
        task.setStatus(ManagementTaskStatus.FAILED);
        when(taskMapper.selectById(101L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(101L));
        verify(itemMapper, never()).selectByTaskId(any());
    }

    @Test
    void resetTaskState_resetsTaskAndItems_withoutRepublish() {
        ManagementTask task = new ManagementTask();
        task.setId(201L);
        task.setTaskType(TaskType.RECOVERY);
        task.setStatus(ManagementTaskStatus.FAILED);
        task.setAttempt(1);
        when(taskMapper.selectById(201L)).thenReturn(task);

        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(2L);
        item.setTaskId(201L);
        item.setTargetType("SYSTEM");
        item.setTargetId(7L);
        item.setOperationType(TaskType.RECOVERY);
        item.setStatus(ManagementTaskStatus.FAILED);
        when(itemMapper.selectByTaskId(201L)).thenReturn(List.of(item));

        service.resetTaskState(201L);

        verify(taskMapper).resetForRetry(eq(201L), eq(2), any());
        verify(itemMapper).resetForRetry(eq(2L), eq(2), anyString(), any());
        verify(importRetryCoordinator, never()).retry(any());
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    @Test
    void updateItemStatus_failedItem_aggregatesErrorMessageToTask() {
        ManagementTask task = new ManagementTask();
        task.setId(301L);
        task.setStatus(ManagementTaskStatus.RUNNING);
        when(taskMapper.selectById(301L)).thenReturn(task);

        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(3L);
        item.setTaskId(301L);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        when(itemMapper.selectById(3L)).thenReturn(item);

        ManagementTaskItem failedItem = new ManagementTaskItem();
        failedItem.setId(3L);
        failedItem.setTaskId(301L);
        failedItem.setStatus(ManagementTaskStatus.FAILED);
        failedItem.setErrorMessage("转码失败: ffmpeg 超时");
        when(itemMapper.selectByTaskId(301L)).thenReturn(List.of(failedItem));
        when(itemMapper.updateStatusIfActive(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.updateItemStatus(3L, ManagementTaskStatus.FAILED, "转码失败: ffmpeg 超时", null, null, 1);

        ManagementTask updatedTask = captorTaskErrorUpdate();
        assertTrue(updatedTask.getErrorMessage().contains("转码失败: ffmpeg 超时"),
                "任务级 errorMessage 应聚合失败 item 的错误");
    }

    @Test
    void updateItemStatus_success_clearsTaskErrorMessage() {
        ManagementTask task = new ManagementTask();
        task.setId(302L);
        task.setStatus(ManagementTaskStatus.RUNNING);
        when(taskMapper.selectById(302L)).thenReturn(task);

        ManagementTaskItem item = new ManagementTaskItem();
        item.setId(4L);
        item.setTaskId(302L);
        item.setStatus(ManagementTaskStatus.RUNNING);
        item.setAttempt(1);
        when(itemMapper.selectById(4L)).thenReturn(item);

        ManagementTaskItem succeededItem = new ManagementTaskItem();
        succeededItem.setId(4L);
        succeededItem.setTaskId(302L);
        succeededItem.setStatus(ManagementTaskStatus.SUCCEEDED);
        when(itemMapper.selectByTaskId(302L)).thenReturn(List.of(succeededItem));
        when(itemMapper.updateStatusIfActive(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.updateItemStatus(4L, ManagementTaskStatus.SUCCEEDED, null, null, null, 1);

        ManagementTask updatedTask = captorTaskErrorUpdate();
        assertTrue(updatedTask.getErrorMessage() == null,
                "非失败终态应显式清空任务级 errorMessage");
    }

    /** 捕获 aggregateTaskStatus 末尾的任务级状态更新。 */
    private ManagementTask captorTaskErrorUpdate() {
        ArgumentCaptor<ManagementTask> captor = ArgumentCaptor.forClass(ManagementTask.class);
        verify(taskMapper).updateById(captor.capture());
        return captor.getValue();
    }
}
