package com.comicatlas.worker.exporter.command;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.exporter.ExportManifestBuildException;
import com.comicatlas.worker.exporter.ExportService;
import com.comicatlas.worker.exporter.publisher.ExportEventPublisher;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExportTaskHandler MQ ACK/REQUEUE 语义契约测试。
 *
 * <p>使用真实 {@link MqConsumerSupport}（ACK/Reject 策略真实执行），断言：
 * 构建/校验失败 → 发布 failed 后正常 ACK；completed 发布失败 → 不发布 failed 并 REQUEUE；
 * requeue 重投 → 复用既有目录结果重新发布 completed 并 ACK；全程绝不发送 ExportTaskFailedEvent。
 */
@ExtendWith(MockitoExtension.class)
class ExportTaskHandlerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ExportService exportService;

    @Mock
    private Channel channel;

    private final MqConsumerSupport mqConsumerSupport = new MqConsumerSupport();
    private ExportTaskHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExportTaskHandler(exportService,
                new ExportEventPublisher(rabbitTemplate), mqConsumerSupport);
    }

    private static ExportTaskCreatedEvent event(Long taskId, Long comicId) {
        return new ExportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId);
    }

    private static ExportService.ExportOutput output(Long taskId, Long comicId, String fileName, long size) {
        return new ExportService.ExportOutput(taskId, comicId, fileName, size);
    }

    @Test
    @DisplayName("happy：started/completed 依次发布，消息 ACK，不发 failed")
    void happyPath_publishesStartedThenCompleted_acksMessage() throws Exception {
        when(exportService.export(1L, 99L))
                .thenReturn(output(99L, 1L, "99/标题_1_20260809_120000.zip", 1234L));

        handler.handle(event(99L, 1L), channel, 5L);

        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.EXPORT), eq(MqRoutingKeys.TASK_STARTED),
                (Object) any(ExportTaskStartedEvent.class));

        ArgumentCaptor<ExportTaskCompletedEvent> completedCaptor = ArgumentCaptor.forClass(ExportTaskCompletedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.EXPORT), eq(MqRoutingKeys.TASK_COMPLETED),
                (Object) completedCaptor.capture());
        ExportTaskCompletedEvent completed = completedCaptor.getValue();
        assertEquals(99L, completed.taskId());
        assertEquals(1L, completed.comicId());
        assertEquals("EXPORT", completed.outputRoot());
        assertEquals("99/标题_1_20260809_120000.zip", completed.outputPath());
        assertEquals(1234L, completed.outputSize());

        verify(channel).basicAck(5L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(rabbitTemplate, never()).convertAndSend(eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.TASK_FAILED), (Object) any());
    }

    @Test
    @DisplayName("构建/校验失败：发布 failed 后正常 ACK，不发 completed 不 requeue")
    void buildFailure_publishesFailed_acksWithoutCompleted() throws Exception {
        when(exportService.export(1L, 99L))
                .thenThrow(new ExportManifestBuildException("导出清单构建失败：comicId=1, manifest 校验失败"));
        when(exportService.classifyExportError(any(Exception.class))).thenReturn("MANIFEST_ERROR");

        handler.handle(event(99L, 1L), channel, 5L);

        ArgumentCaptor<ExportTaskFailedEvent> failedCaptor = ArgumentCaptor.forClass(ExportTaskFailedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.EXPORT), eq(MqRoutingKeys.TASK_FAILED),
                (Object) failedCaptor.capture());
        assertEquals(99L, failedCaptor.getValue().taskId());
        assertEquals(1L, failedCaptor.getValue().comicId());
        assertEquals("MANIFEST_ERROR", failedCaptor.getValue().errorCode());

        verify(rabbitTemplate, never()).convertAndSend(eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.TASK_COMPLETED), (Object) any());
        verify(channel).basicAck(5L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("completed 发布失败：不发布 failed，消息 REQUEUE（basicReject requeue=true）")
    void completedPublishFailure_rejectsWithRequeue_noFailedEvent() throws Exception {
        when(exportService.export(1L, 99L)).thenReturn(output(99L, 1L, "99/x.zip", 10L));
        doAnswer(inv -> {
            String routingKey = inv.getArgument(1);
            if (MqRoutingKeys.TASK_COMPLETED.equals(routingKey)) {
                throw new RuntimeException("MQ 连接中断");
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(MqExchanges.EXPORT), anyString(), (Object) any());

        handler.handle(event(99L, 1L), channel, 5L);

        verify(channel).basicReject(5L, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(rabbitTemplate, never()).convertAndSend(eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.TASK_FAILED), (Object) any());
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.EXPORT), eq(MqRoutingKeys.TASK_COMPLETED), (Object) any());
    }

    @Test
    @DisplayName("requeue 重投：第二次复用既有结果重新发布 completed 并 ACK，全程不发 failed")
    void redeliveryAfterCompletedFailure_reusesExistingResultAndPublishesCompleted() throws Exception {
        when(exportService.export(1L, 99L))
                .thenReturn(output(99L, 1L, "99/标题_1_20260809_120000.zip", 1234L));
        AtomicInteger completedAttempts = new AtomicInteger();
        doAnswer(inv -> {
            String routingKey = inv.getArgument(1);
            if (MqRoutingKeys.TASK_COMPLETED.equals(routingKey) && completedAttempts.getAndIncrement() == 0) {
                throw new RuntimeException("MQ 连接中断");
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(MqExchanges.EXPORT), anyString(), (Object) any());

        // 第一次投递：completed 发布失败 → requeue（不 ack、不发 failed）
        handler.handle(event(99L, 1L), channel, 1L);
        verify(channel).basicReject(1L, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());

        // 第二次投递（requeue 重投）：复用既有目录结果，重新发布 completed → ACK
        handler.handle(event(99L, 1L), channel, 2L);
        ArgumentCaptor<ExportTaskCompletedEvent> completedCaptor = ArgumentCaptor.forClass(ExportTaskCompletedEvent.class);
        verify(rabbitTemplate, times(2)).convertAndSend(eq(MqExchanges.EXPORT), eq(MqRoutingKeys.TASK_COMPLETED),
                (Object) completedCaptor.capture());
        List<ExportTaskCompletedEvent> completedEvents = completedCaptor.getAllValues();
        assertEquals(2, completedEvents.size(), "两次投递各发布一次 completed（首次失败、重投成功）");
        ExportTaskCompletedEvent redelivered = completedEvents.get(1);
        assertEquals("99/标题_1_20260809_120000.zip", redelivered.outputPath());
        assertEquals(1234L, redelivered.outputSize());
        verify(channel).basicAck(2L, false);
        verify(channel, times(1)).basicReject(anyLong(), eq(true));

        // 全程不得发送 ExportTaskFailedEvent
        verify(rabbitTemplate, never()).convertAndSend(eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.TASK_FAILED), (Object) any());
    }
}
