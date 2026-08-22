package com.comicatlas.api.task.service;

import com.comicatlas.api.exporter.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.task.entity.ManagementTask;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.mapper.ManagementTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private TaskAggregationService taskAggregationService;

    @InjectMocks
    private ManagementTaskService service;

    /** 纯 mock 环境无 MyBatis 容器，需预注册 TableInfo 供 LambdaUpdateWrapper 解析列名。 */
    @BeforeAll
    static void initMybatisTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ManagementTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ManagementTaskItem.class);
    }

    @BeforeEach
    void injectAggregationService() {
        ReflectionTestUtils.setField(service, "taskAggregationService", taskAggregationService);
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
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        // 导入任务非终态且非 PENDING：说明与管理任务状态不一致，重试入队应抛冲突回滚而非静默卡死
        org.mockito.Mockito.doThrow(new BusinessException(409, "导入任务非终态且未被重置"))
                .when(taskRetryPublisher).publish(eq(99L), eq(item), eq(2));

        assertThrows(BusinessException.class, () -> service.retryTask(99L));
    }

    @Test
    void retryTask_recoveryTask_throwsConflict() {
        ManagementTask task = new ManagementTask();
        task.setId(100L);
        task.setTaskType(TaskType.RECOVERY);
        task.setStatus(ManagementTaskStatus.FAILED);
        when(taskMapper.selectById(100L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(100L));
        verify(itemMapper, never()).selectList(any());
    }

    @Test
    void retryTask_scanTask_throwsConflict() {
        ManagementTask task = new ManagementTask();
        task.setId(101L);
        task.setTaskType(TaskType.DIRECTORY_SCAN);
        task.setStatus(ManagementTaskStatus.FAILED);
        when(taskMapper.selectById(101L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(101L));
        verify(itemMapper, never()).selectList(any());
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
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        service.resetTaskState(201L);

        verify(taskMapper).update(eq(null), any());
        verify(itemMapper).update(eq(null), any());
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
        when(itemMapper.selectList(any())).thenReturn(List.of(failedItem));

        service.updateItemStatus(3L, ManagementTaskStatus.FAILED, "转码失败: ffmpeg 超时", null, null, 1);

        @SuppressWarnings("unchecked")
        LambdaUpdateWrapper<ManagementTask> errorWrapper = captorTaskErrorUpdate();
        assertTrue(errorWrapper.getSqlSet().contains("error_message"),
                "任务级 errorMessage 应聚合失败 item 的错误");
        assertTrue(errorWrapper.getParamNameValuePairs().containsValue("转码失败: ffmpeg 超时"));
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
        when(itemMapper.selectList(any())).thenReturn(List.of(succeededItem));

        service.updateItemStatus(4L, ManagementTaskStatus.SUCCEEDED, null, null, null, 1);

        @SuppressWarnings("unchecked")
        LambdaUpdateWrapper<ManagementTask> errorWrapper = captorTaskErrorUpdate();
        assertTrue(errorWrapper.getSqlSet().contains("error_message"),
                "非失败终态应显式清空任务级 errorMessage");
    }

    /** 捕获 aggregateTaskStatus 末尾的任务级 errorMessage 条件更新（唯一 update(null, wrapper) 调用）。 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ManagementTask> captorTaskErrorUpdate() {
        ArgumentCaptor<Wrapper<ManagementTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).update(eq(null), captor.capture());
        return (LambdaUpdateWrapper<ManagementTask>) captor.getValue();
    }
}
