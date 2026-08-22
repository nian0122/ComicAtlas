package com.comicatlas.api.importer.event;

import com.comicatlas.api.recovery.dto.RecoveryProgressVO;
import com.comicatlas.api.recovery.RecoveryEngine;
import com.comicatlas.api.recovery.enums.RecoveryTaskStatus;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
class RecoveryEventHandlerTest {

    @Mock
    private RecoveryEngine recoveryEngine;

    @Mock
    private RecoveryTaskMapper recoveryTaskMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ManagementTaskService managementTaskService;

    @Mock
    private Channel channel;

    private RecoveryEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RecoveryEventHandler(recoveryEngine, recoveryTaskMapper, redisTemplate, managementTaskService, new MqConsumerSupport());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        lenient().doNothing().when(valueOperations).set(anyString(), any(), any(Duration.class));
        lenient().when(managementTaskService.findActiveItem(any(), any(), any())).thenReturn(null);
    }

    // ======================== handleScanCompleted ========================

    @Test
    void handleScanCompleted_shouldCallRecoveryEngine_forEachComicId() throws Exception {
        UUID eventId = UUID.randomUUID();
        long taskId = 100L;
        List<Long> comicIds = List.of(1L, 2L, 3L);

        RecoveryTask task = new RecoveryTask();
        task.setId(taskId);
        task.setStatus(RecoveryTaskStatus.QUEUED);
        when(recoveryTaskMapper.selectById(taskId)).thenReturn(task);

        RecoveryProgressVO progress = new RecoveryProgressVO(1, 1, 0, 0, 0, null, 3, 30);
        when(recoveryEngine.processComicDir(anyLong(), anyInt())).thenReturn(progress);

        when(recoveryTaskMapper.updateById(any(RecoveryTask.class))).thenReturn(1);
        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryScanCompletedEvent event = new RecoveryScanCompletedEvent(
                eventId, Instant.now(), taskId, comicIds);

        handler.handle(event, channel, 1L);

        // 验证 RecoveryEngine 被调用了 3 次（每个 comicId 一次）
        verify(recoveryEngine, times(3)).processComicDir(anyLong(), anyInt());
        verify(recoveryEngine).processComicDir(eq(1L), anyInt());
        verify(recoveryEngine).processComicDir(eq(2L), anyInt());
        verify(recoveryEngine).processComicDir(eq(3L), anyInt());

        // 验证任务状态更新为 SUCCEEDED
        ArgumentCaptor<RecoveryTask> taskCaptor = ArgumentCaptor.forClass(RecoveryTask.class);
        verify(recoveryTaskMapper, atLeastOnce()).updateById(taskCaptor.capture());
        RecoveryTask lastUpdate = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);
        assertEquals(RecoveryTaskStatus.SUCCEEDED, lastUpdate.getStatus());
        assertNotNull(lastUpdate.getEndedAt());

        // 验证 ack
        verify(channel).basicAck(1L, false);
    }

    @Test
    void handleScanCompleted_shouldSkip_whenTaskAlreadySuccess() throws Exception {
        UUID eventId = UUID.randomUUID();
        long taskId = 200L;

        RecoveryTask task = new RecoveryTask();
        task.setId(taskId);
        task.setStatus(RecoveryTaskStatus.SUCCEEDED);
        when(recoveryTaskMapper.selectById(taskId)).thenReturn(task);

        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryScanCompletedEvent event = new RecoveryScanCompletedEvent(
                eventId, Instant.now(), taskId, List.of(1L));

        handler.handle(event, channel, 1L);

        // 不应该调用 RecoveryEngine
        verify(recoveryEngine, never()).processComicDir(anyLong(), anyInt());
        // 应该直接 ack
        verify(channel).basicAck(1L, false);
    }

    @Test
    void handleScanCompleted_shouldSkip_whenTaskAlreadyFailed() throws Exception {
        UUID eventId = UUID.randomUUID();
        long taskId = 300L;

        RecoveryTask task = new RecoveryTask();
        task.setId(taskId);
        task.setStatus(RecoveryTaskStatus.FAILED);
        when(recoveryTaskMapper.selectById(taskId)).thenReturn(task);

        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryScanCompletedEvent event = new RecoveryScanCompletedEvent(
                eventId, Instant.now(), taskId, List.of(1L));

        handler.handle(event, channel, 1L);

        verify(recoveryEngine, never()).processComicDir(anyLong(), anyInt());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void handleScanCompleted_shouldContinueProgressing_whenSingleComicFails() throws Exception {
        UUID eventId = UUID.randomUUID();
        long taskId = 400L;
        List<Long> comicIds = List.of(10L, 20L);

        RecoveryTask task = new RecoveryTask();
        task.setId(taskId);
        task.setStatus(RecoveryTaskStatus.QUEUED);
        when(recoveryTaskMapper.selectById(taskId)).thenReturn(task);

        // 第1个漫画成功
        RecoveryProgressVO success = new RecoveryProgressVO(1, 1, 0, 0, 0, null, 1, 10);
        when(recoveryEngine.processComicDir(eq(10L), anyInt())).thenReturn(success);

        // 第2个漫画失败（抛出异常）
        when(recoveryEngine.processComicDir(eq(20L), anyInt()))
                .thenThrow(new RuntimeException("metadata 损坏"));

        when(recoveryTaskMapper.updateById(any(RecoveryTask.class))).thenReturn(1);
        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryScanCompletedEvent event = new RecoveryScanCompletedEvent(
                eventId, Instant.now(), taskId, comicIds);

        handler.handle(event, channel, 1L);

        // 两个 comic 都应该被处理
        verify(recoveryEngine, times(2)).processComicDir(anyLong(), anyInt());

        // 最终状态应该是 SUCCEEDED（单个失败不中断整体）
        ArgumentCaptor<RecoveryTask> taskCaptor = ArgumentCaptor.forClass(RecoveryTask.class);
        verify(recoveryTaskMapper, atLeastOnce()).updateById(taskCaptor.capture());
        RecoveryTask lastUpdate = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);
        assertEquals(RecoveryTaskStatus.SUCCEEDED, lastUpdate.getStatus());
        assertEquals(1, lastUpdate.getErrorComics()); // 1 个 comic 出错

        verify(channel).basicAck(1L, false);
    }

    // ======================== handleFailed ========================

    @Test
    void handleFailed_shouldSetTaskToFailed_withErrorMessage() throws Exception {
        UUID eventId = UUID.randomUUID();
        long taskId = 500L;

        RecoveryTask task = new RecoveryTask();
        task.setId(taskId);
        task.setStatus(RecoveryTaskStatus.RUNNING);
        when(recoveryTaskMapper.selectById(taskId)).thenReturn(task);

        when(recoveryTaskMapper.updateById(any(RecoveryTask.class))).thenReturn(1);
        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryFailedEvent event = new RecoveryFailedEvent(
                eventId, Instant.now(), taskId, "Worker 磁盘空间不足");

        handler.handle(event, channel, 1L);

        ArgumentCaptor<RecoveryTask> taskCaptor = ArgumentCaptor.forClass(RecoveryTask.class);
        verify(recoveryTaskMapper).updateById(taskCaptor.capture());
        RecoveryTask updated = taskCaptor.getValue();
        assertEquals(RecoveryTaskStatus.FAILED, updated.getStatus());
        assertEquals("Worker 磁盘空间不足", updated.getErrorMessage());
        assertNotNull(updated.getEndedAt());

        verify(channel).basicAck(1L, false);
    }

    @Test
    void handleFailed_shouldSkip_whenTaskAlreadySuccess() throws Exception {
        UUID eventId = UUID.randomUUID();
        long taskId = 600L;

        RecoveryTask task = new RecoveryTask();
        task.setId(taskId);
        task.setStatus(RecoveryTaskStatus.SUCCEEDED);
        when(recoveryTaskMapper.selectById(taskId)).thenReturn(task);

        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryFailedEvent event = new RecoveryFailedEvent(
                eventId, Instant.now(), taskId, "不应生效");

        handler.handle(event, channel, 1L);

        // 不应更新
        verify(recoveryTaskMapper, never()).updateById(any(RecoveryTask.class));
        verify(channel).basicAck(1L, false);
    }

    // ======================== 幂等性 ========================

    @Test
    void handleScanCompleted_shouldSkip_whenIdempotentKeyExists() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.hasKey("mq:event:" + eventId)).thenReturn(true);
        doNothing().when(channel).basicAck(anyLong(), eq(false));

        RecoveryScanCompletedEvent event = new RecoveryScanCompletedEvent(
                eventId, Instant.now(), 700L, List.of(1L));

        handler.handle(event, channel, 1L);

        verify(recoveryTaskMapper, never()).selectById(anyLong());
        verify(recoveryEngine, never()).processComicDir(anyLong(), anyInt());
        verify(channel).basicAck(1L, false);
    }
}
