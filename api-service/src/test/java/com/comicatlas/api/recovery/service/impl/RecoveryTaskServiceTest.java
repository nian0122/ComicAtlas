package com.comicatlas.api.recovery.application.service.impl;

import com.comicatlas.api.recovery.application.service.impl.RecoveryTaskServiceImpl;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.CreateCommand;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.RecoveryTaskSnapshot;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.UpdateCommand;
import com.comicatlas.api.recovery.domain.model.RecoveryTaskStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.RecoveryRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class RecoveryTaskServiceTest {

    @Mock
    private RecoveryTaskPersistencePort persistencePort;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ManagementTaskService managementTaskService;

    @InjectMocks
    private RecoveryTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(managementTaskService.createTask(any(), any(), any())).thenAnswer(invocation -> {
            ManagementTaskResponse resp = new ManagementTaskResponse();
            resp.setId(500L);
            return resp;
        });
    }

    // ======================== createRecoveryTask ========================

    /**
     * 验证创建恢复任务的核心业务逻辑：检查无活跃任务时，正确创建 QUEUED 状态任务，
     * 且同事务向 Outbox 写入恢复请求事件（由 relay 异步发布，不直接操作 MQ）。
     */
    @Test
    void createRecoveryTask_shouldCreatePendingTask_whenNoActiveTask() {
        when(persistencePort.countActiveTasks()).thenReturn(0L);

        // 捕获 insert 的参数以验证任务初始状态
        ArgumentCaptor<CreateCommand> taskCaptor = ArgumentCaptor.forClass(CreateCommand.class);
        when(persistencePort.insert(any(CreateCommand.class))).thenReturn(1L);

        service.createRecoveryTask();

        verify(persistencePort).insert(taskCaptor.capture());
        CreateCommand captured = taskCaptor.getValue();
        assertEquals(RecoveryTaskStatus.QUEUED, captured.status());
        assertEquals(0, captured.totalComics());
        assertEquals(0, captured.recoveredComics());
        assertEquals(0, captured.skippedComics());
        assertEquals(0, captured.placeholderComics());
        assertEquals(0, captured.errorComics());
        assertEquals(0, captured.retryCount());

        // 恢复请求事件走 Outbox（同事务），而非直接发布 MQ
        verify(outboxService).enqueue(any(RecoveryRequestedEvent.class),
                eq(MqExchanges.RECOVERY), eq(MqRoutingKeys.RECOVERY_REQUESTED));
    }

    @Test
    void createRecoveryTask_shouldThrow409_whenRunningTaskExists() {
        when(persistencePort.countActiveTasks()).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createRecoveryTask());
        assertEquals(409, ex.getCode());
        assertEquals("已有恢复任务正在执行", ex.getMessage());

        verify(persistencePort, never()).insert(any(CreateCommand.class));
    }

    @Test
    void createRecoveryTask_shouldThrow409_whenPendingTaskExists() {
        when(persistencePort.countActiveTasks()).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createRecoveryTask());
        assertEquals(409, ex.getCode());

        verify(persistencePort, never()).insert(any(CreateCommand.class));
    }

    // ======================== retryTask ========================

    @Test
    void retryTask_shouldUpdateToPending_whenStatusFailed() {
        RecoveryTaskSnapshot failed = snapshot(2L, RecoveryTaskStatus.FAILED, 1);

        when(persistencePort.findById(2L)).thenReturn(failed);

        service.retryTask(2L);

        ArgumentCaptor<UpdateCommand> taskCaptor = ArgumentCaptor.forClass(UpdateCommand.class);
        verify(persistencePort).update(taskCaptor.capture());
        UpdateCommand updated = taskCaptor.getValue();
        assertEquals(RecoveryTaskStatus.QUEUED, updated.status());
        assertEquals(2, updated.retryCount());
        assertNull(updated.errorMessage());
        assertNull(updated.startedAt());
        assertNull(updated.endedAt());

        // 重试后同事务向 Outbox 重新写入恢复请求事件
        verify(outboxService).enqueue(any(RecoveryRequestedEvent.class),
                eq(MqExchanges.RECOVERY), eq(MqRoutingKeys.RECOVERY_REQUESTED));
    }

    @Test
    void retryTask_shouldThrow_whenTaskNotFound() {
        when(persistencePort.findById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(99L));
        assertEquals(404, ex.getCode());
        assertEquals("任务不存在", ex.getMessage());
    }

    @Test
    void retryTask_shouldThrow_whenStatusSuccess() {
        RecoveryTaskSnapshot success = snapshot(3L, RecoveryTaskStatus.SUCCEEDED, 0);

        when(persistencePort.findById(3L)).thenReturn(success);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(3L));
        assertEquals(400, ex.getCode());
        assertEquals("仅 FAILED 状态可重试", ex.getMessage());
    }

    @Test
    void retryTask_shouldThrow_whenStatusRunning() {
        RecoveryTaskSnapshot running = snapshot(4L, RecoveryTaskStatus.RUNNING, 0);

        when(persistencePort.findById(4L)).thenReturn(running);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(4L));
        assertEquals(400, ex.getCode());
        assertEquals("仅 FAILED 状态可重试", ex.getMessage());
    }

    @Test
    void retryTask_shouldThrow_whenStatusPending() {
        RecoveryTaskSnapshot pending = snapshot(5L, RecoveryTaskStatus.QUEUED, 0);

        when(persistencePort.findById(5L)).thenReturn(pending);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(5L));
        assertEquals(400, ex.getCode());
        assertEquals("仅 FAILED 状态可重试", ex.getMessage());
    }

    // ======================== getTaskDetail ========================

    @Test
    void getTaskDetail_shouldThrow404_whenNotFound() {
        when(persistencePort.findById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTaskDetail(99L));
        assertEquals(404, ex.getCode());
        assertEquals("任务不存在", ex.getMessage());
    }

    private RecoveryTaskSnapshot snapshot(long taskId, RecoveryTaskStatus status, int retryCount) {
        return new RecoveryTaskSnapshot(taskId, null, status, 0, 0, 0, 0, 0,
                "磁盘空间不足", null, retryCount, null, null, null);
    }
}
