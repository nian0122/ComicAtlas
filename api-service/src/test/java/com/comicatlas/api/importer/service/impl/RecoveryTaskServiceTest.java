package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.event.RecoveryEventPublisher;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.any;
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
    private RecoveryTaskMapper recoveryTaskMapper;

    @Mock
    private RecoveryEventPublisher recoveryEventPublisher;

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
     * 验证创建恢复任务的核心业务逻辑：检查无活跃任务时，正确创建 PENDING 状态任务。
     * 不依赖 Spring Transaction 上下文，仅验证事务边界前的业务状态。
     */
    @Test
    void createRecoveryTask_shouldCreatePendingTask_whenNoActiveTask() {
        when(recoveryTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        // 捕获 insert 的参数以验证任务初始状态
        ArgumentCaptor<RecoveryTask> taskCaptor = ArgumentCaptor.forClass(RecoveryTask.class);
        // MyBatis Plus insert 在无事务时也会执行，只需 verify 不抛异常
        when(recoveryTaskMapper.insert(any(RecoveryTask.class))).thenReturn(1);

        // TransactionSynchronizationManager.registerSynchronization() 在无 Spring 事务时抛 IllegalStateException
        // 这是预期行为，验证抛出前 insert 已正确执行
        assertThrows(IllegalStateException.class, () -> service.createRecoveryTask());

        verify(recoveryTaskMapper).insert(taskCaptor.capture());
        RecoveryTask captured = taskCaptor.getValue();
        assertEquals("QUEUED", captured.getStatus());
        assertEquals(0, captured.getTotalComics());
        assertEquals(0, captured.getRecoveredComics());
        assertEquals(0, captured.getSkippedComics());
        assertEquals(0, captured.getPlaceholderComics());
        assertEquals(0, captured.getErrorComics());
        assertEquals(0, captured.getRetryCount());
    }

    @Test
    void createRecoveryTask_shouldThrow409_whenRunningTaskExists() {
        when(recoveryTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createRecoveryTask());
        assertEquals(409, ex.getCode());
        assertEquals("已有恢复任务正在执行", ex.getMessage());

        verify(recoveryTaskMapper, never()).insert(any(RecoveryTask.class));
    }

    @Test
    void createRecoveryTask_shouldThrow409_whenPendingTaskExists() {
        when(recoveryTaskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createRecoveryTask());
        assertEquals(409, ex.getCode());

        verify(recoveryTaskMapper, never()).insert(any(RecoveryTask.class));
    }

    // ======================== retryTask ========================

    @Test
    void retryTask_shouldUpdateToPending_whenStatusFailed() {
        RecoveryTask failed = new RecoveryTask();
        failed.setId(2L);
        failed.setStatus("FAILED");
        failed.setRetryCount(1);
        failed.setErrorMessage("磁盘空间不足");

        when(recoveryTaskMapper.selectById(2L)).thenReturn(failed);
        when(recoveryTaskMapper.updateById(any(RecoveryTask.class))).thenReturn(1);

        // TransactionSynchronizationManager.registerSynchronization() 在无 Spring 事务上下文时抛异常
        // 验证在异常抛出前，updateById 已正确执行重置任务状态
        assertThrows(IllegalStateException.class, () -> service.retryTask(2L));

        ArgumentCaptor<RecoveryTask> taskCaptor = ArgumentCaptor.forClass(RecoveryTask.class);
        verify(recoveryTaskMapper).updateById(taskCaptor.capture());
        RecoveryTask updated = taskCaptor.getValue();
        assertEquals("QUEUED", updated.getStatus());
        assertEquals(2, updated.getRetryCount());
        assertNull(updated.getErrorMessage());
        assertNull(updated.getStartedAt());
        assertNull(updated.getEndedAt());
    }

    @Test
    void retryTask_shouldThrow_whenTaskNotFound() {
        when(recoveryTaskMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(99L));
        assertEquals(404, ex.getCode());
        assertEquals("任务不存在", ex.getMessage());
    }

    @Test
    void retryTask_shouldThrow_whenStatusSuccess() {
        RecoveryTask success = new RecoveryTask();
        success.setId(3L);
        success.setStatus("SUCCESS");
        success.setRetryCount(0);

        when(recoveryTaskMapper.selectById(3L)).thenReturn(success);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(3L));
        assertEquals(400, ex.getCode());
        assertEquals("仅 FAILED 状态可重试", ex.getMessage());
    }

    @Test
    void retryTask_shouldThrow_whenStatusRunning() {
        RecoveryTask running = new RecoveryTask();
        running.setId(4L);
        running.setStatus("RUNNING");
        running.setRetryCount(0);

        when(recoveryTaskMapper.selectById(4L)).thenReturn(running);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(4L));
        assertEquals(400, ex.getCode());
        assertEquals("仅 FAILED 状态可重试", ex.getMessage());
    }

    @Test
    void retryTask_shouldThrow_whenStatusPending() {
        RecoveryTask pending = new RecoveryTask();
        pending.setId(5L);
        pending.setStatus("PENDING");
        pending.setRetryCount(0);

        when(recoveryTaskMapper.selectById(5L)).thenReturn(pending);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.retryTask(5L));
        assertEquals(400, ex.getCode());
        assertEquals("仅 FAILED 状态可重试", ex.getMessage());
    }

    // ======================== getTaskDetail ========================

    @Test
    void getTaskDetail_shouldThrow404_whenNotFound() {
        when(recoveryTaskMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTaskDetail(99L));
        assertEquals(404, ex.getCode());
        assertEquals("任务不存在", ex.getMessage());
    }
}
