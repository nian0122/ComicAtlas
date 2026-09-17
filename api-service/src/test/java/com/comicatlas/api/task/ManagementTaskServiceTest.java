package com.comicatlas.api.task.application.service;

import com.comicatlas.api.task.application.assembler.TaskResponseAssembler;
import com.comicatlas.api.exporter.infrastructure.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.application.service.ImportRetryCoordinator;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskMapper;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository.ManagementTaskAggregationSnapshot;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository.ManagementTaskAggregationUpdate;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository.ManagementTaskItemSnapshot;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
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
import com.comicatlas.api.task.application.service.impl.ManagementTaskServiceImpl;
import com.comicatlas.api.task.application.port.in.TaskQueryService;
import com.comicatlas.api.task.application.port.out.TaskRetryPublisher;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.TaskSnapshot;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.ItemSnapshot;
import com.comicatlas.api.metadata.application.service.MetadataRefreshTaskPolicy;
import com.comicatlas.api.metadata.application.port.out.MetadataRefreshTaskPersistencePort;
import com.comicatlas.api.task.application.service.impl.TaskAggregationServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

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
    @Mock private TaskQueryPersistencePort taskQueryPersistencePort;
    @Mock private MetadataRefreshTaskPersistencePort metadataRefreshTaskPersistencePort;
    @Mock private ManagementTaskAggregationRepository aggregationRepository;
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
        TaskSnapshot task = taskSnapshot(99L, TaskType.IMPORT, ManagementTaskStatus.FAILED, 1);
        when(taskQueryPersistencePort.findTask(99L)).thenReturn(task);

        ItemSnapshot item = itemSnapshot(1L, 99L, "COMIC", 10L, TaskType.IMPORT,
                ManagementTaskStatus.FAILED, 1);
        when(taskQueryPersistencePort.findItemsByTaskId(99L)).thenReturn(List.of(item));

        // 导入任务非终态且非 PENDING：说明与管理任务状态不一致，重试入队应抛冲突回滚而非静默卡死
        org.mockito.Mockito.doThrow(new BusinessException(409, "导入任务非终态且未被重置"))
                .when(taskRetryPublisher).publish(eq(99L), eq(new TaskRetryPublisher.RetryItem(
                        item.id(), item.operationType(), item.resultRefType(), item.resultRefId(),
                        item.targetType(), item.targetId())), eq(2));

        assertThrows(BusinessException.class, () -> service.retryTask(99L));
    }

    @Test
    void createMetadataRefreshTask_同漫画多章节只占用一次漫画状态() {
        CreateManagementTaskRequest request = new CreateManagementTaskRequest();
        request.setTaskType(TaskType.METADATA_REFRESH);
        request.setOperation("刷新元数据");
        request.setTargetType("COMIC");
        request.setTargets(List.of(chapterTarget(11L), chapterTarget(12L)));
        when(metadataRefreshTaskPersistencePort.findComicIdsByChapterIds(any())).thenReturn(List.of(1L, 1L));
        when(metadataRefreshTaskPersistencePort.lockComicForRefresh(1L)).thenReturn(1);
        when(taskQueryPersistencePort.countItemsByLockKey(anyString())).thenReturn(0L);

        service.createTask(request, null, "{}");

        verify(taskQueryPersistencePort, times(2)).insertTaskItem(any(TaskQueryPersistencePort.CreateItemCommand.class));
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
        TaskSnapshot task = taskSnapshot(100L, TaskType.RECOVERY, ManagementTaskStatus.FAILED, 1);
        when(taskQueryPersistencePort.findTask(100L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(100L));
        verify(taskQueryPersistencePort, never()).findItemsByTaskId(any());
    }

    @Test
    void retryTask_scanTask_throwsConflict() {
        TaskSnapshot task = taskSnapshot(101L, TaskType.DIRECTORY_SCAN, ManagementTaskStatus.FAILED, 1);
        when(taskQueryPersistencePort.findTask(101L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(101L));
        verify(taskQueryPersistencePort, never()).findItemsByTaskId(any());
    }

    @Test
    void resetTaskState_resetsTaskAndItems_withoutRepublish() {
        TaskSnapshot task = taskSnapshot(201L, TaskType.RECOVERY, ManagementTaskStatus.FAILED, 1);
        when(taskQueryPersistencePort.findTask(201L)).thenReturn(task);

        ItemSnapshot item = itemSnapshot(2L, 201L, "SYSTEM", 7L, TaskType.RECOVERY,
                ManagementTaskStatus.FAILED, 1);
        when(taskQueryPersistencePort.findItemsByTaskId(201L)).thenReturn(List.of(item));

        service.resetTaskState(201L);

        verify(taskQueryPersistencePort).resetTask(eq(201L), eq(2), any());
        verify(taskQueryPersistencePort).resetItem(eq(2L), eq(2), anyString(), any());
        verify(importRetryCoordinator, never()).retry(any());
        verify(outboxService, never()).enqueue(any(), any(), any());
    }

    @Test
    void updateItemStatus_failedItem_aggregatesErrorMessageToTask() {
        ItemSnapshot item = itemSnapshot(3L, 301L, "MEDIA", 1L, TaskType.TRANSCODE,
                ManagementTaskStatus.RUNNING, 1);
        when(taskQueryPersistencePort.findItem(3L)).thenReturn(item);

        when(aggregationRepository.findByTaskId(301L)).thenReturn(Optional.of(
                new ManagementTaskAggregationSnapshot(301L, ManagementTaskStatus.RUNNING, null,
                        List.of(new ManagementTaskItemSnapshot(ManagementTaskStatus.FAILED,
                                "转码失败: ffmpeg 超时")))));
        when(taskQueryPersistencePort.updateItemStatus(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.updateItemStatus(3L, ManagementTaskStatus.FAILED, "转码失败: ffmpeg 超时", null, null, 1);

        ManagementTaskAggregationUpdate updatedTask = captorTaskErrorUpdate();
        assertTrue(updatedTask.errorMessage().contains("转码失败: ffmpeg 超时"),
                "任务级 errorMessage 应聚合失败 item 的错误");
    }

    @Test
    void updateItemStatus_success_clearsTaskErrorMessage() {
        ItemSnapshot item = itemSnapshot(4L, 302L, "MEDIA", 1L, TaskType.TRANSCODE,
                ManagementTaskStatus.RUNNING, 1);
        when(taskQueryPersistencePort.findItem(4L)).thenReturn(item);

        when(aggregationRepository.findByTaskId(302L)).thenReturn(Optional.of(
                new ManagementTaskAggregationSnapshot(302L, ManagementTaskStatus.RUNNING, null,
                        List.of(new ManagementTaskItemSnapshot(ManagementTaskStatus.SUCCEEDED, null)))));
        when(taskQueryPersistencePort.updateItemStatus(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.updateItemStatus(4L, ManagementTaskStatus.SUCCEEDED, null, null, null, 1);

        ManagementTaskAggregationUpdate updatedTask = captorTaskErrorUpdate();
        assertTrue(updatedTask.errorMessage() == null,
                "非失败终态应显式清空任务级 errorMessage");
    }

    /** 捕获 aggregateTaskStatus 末尾的任务级状态更新。 */
    private ManagementTaskAggregationUpdate captorTaskErrorUpdate() {
        ArgumentCaptor<ManagementTaskAggregationUpdate> captor =
                ArgumentCaptor.forClass(ManagementTaskAggregationUpdate.class);
        verify(aggregationRepository).update(any(), captor.capture());
        return captor.getValue();
    }

    private static TaskSnapshot taskSnapshot(Long id, TaskType taskType,
                                             ManagementTaskStatus status, Integer attempt) {
        return new TaskSnapshot(id, taskType, "测试", "COMIC", null, false, status, null,
                0, 1, 0, 0, 0, null, attempt, 1, null, null, null, null, null);
    }

    private static ItemSnapshot itemSnapshot(Long id, Long taskId, String targetType, Long targetId,
                                             TaskType operationType, ManagementTaskStatus status,
                                             Integer attempt) {
        return new ItemSnapshot(id, taskId, targetType, targetId, operationType, status, attempt, 0,
                targetType + ":" + targetId + ":" + operationType.name(), null, null, null, 1,
                null, null, null, null);
    }
}
