package com.comicatlas.api.importer.event;

import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportMetadataRefreshCompletedEvent;
import com.comicatlas.common.event.ImportMetadataRefreshFailedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导入元数据重建结果事件 Handler 单元测试（TDD）。
 * <p>
 * Handler 只做协议适配：MQ 消费 → inbox 幂等 → 委托 Service；业务编排位于 Service。
 */
@ExtendWith(MockitoExtension.class)
class ImportMetadataRefreshResultHandlerTest {

    @Mock private ImportPersistenceService importPersistenceService;
    @Mock private InboxService inboxService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private Channel channel;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Spy private MqConsumerSupport mqConsumerSupport = new MqConsumerSupport();

    @InjectMocks private ImportMetadataRefreshResultHandler handler;

    private void runInTransaction() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("completed 事件：委托 Service.applyMetadataRefreshCompleted 并标记 inbox")
    void handleMetadataRefreshCompleted_delegatesToService_andMarksInbox() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);

        var event = new ImportMetadataRefreshCompletedEvent(UUID.randomUUID(), Instant.now(), 10L, 100L);
        handler.handleMetadataRefreshCompleted(event, channel, 1L);

        verify(importPersistenceService).applyMetadataRefreshCompleted(event);
        verify(inboxService).markProcessed(eq(event.eventId().toString()), anyString());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("completed 重复事件：inbox 幂等跳过，不重复委托 Service")
    void handleMetadataRefreshCompleted_duplicateEvent_skipsService() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(true);

        var event = new ImportMetadataRefreshCompletedEvent(UUID.randomUUID(), Instant.now(), 10L, 100L);
        handler.handleMetadataRefreshCompleted(event, channel, 1L);

        verify(importPersistenceService, never()).applyMetadataRefreshCompleted(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("failed 事件：委托 Service.applyMetadataRefreshFailed 并标记 inbox")
    void handleMetadataRefreshFailed_delegatesToService_andMarksInbox() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);

        var event = new ImportMetadataRefreshFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, "METADATA_REFRESH_FAILED", "重建失败");
        handler.handleMetadataRefreshFailed(event, channel, 1L);

        verify(importPersistenceService).applyMetadataRefreshFailed(event);
        verify(inboxService).markProcessed(eq(event.eventId().toString()), anyString());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("failed 重复事件：inbox 幂等跳过，不重复委托 Service")
    void handleMetadataRefreshFailed_duplicateEvent_skipsService() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(true);

        var event = new ImportMetadataRefreshFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, "X", "y");
        handler.handleMetadataRefreshFailed(event, channel, 1L);

        verify(importPersistenceService, never()).applyMetadataRefreshFailed(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("Handler 监听的队列常量与契约一致")
    void listenerQueues_matchFrozenContract() {
        verifyQueueMapping(handlerCompletedListener(), MqQueues.IMPORT_METADATA_REFRESH_COMPLETED);
        verifyQueueMapping(handlerFailedListener(), MqQueues.IMPORT_METADATA_REFRESH_FAILED);
    }

    private static void verifyQueueMapping(java.lang.reflect.Method listener, String expectedQueue) {
        org.springframework.amqp.rabbit.annotation.RabbitListener annotation =
                listener.getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assert annotation != null && expectedQueue.equals(annotation.queues()[0])
                : "监听队列与契约不符: expected=" + expectedQueue;
    }

    private static java.lang.reflect.Method handlerCompletedListener() {
        return findListener("handleMetadataRefreshCompleted");
    }

    private static java.lang.reflect.Method handlerFailedListener() {
        return findListener("handleMetadataRefreshFailed");
    }

    private static java.lang.reflect.Method findListener(String name) {
        for (java.lang.reflect.Method method : ImportMetadataRefreshResultHandler.class.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.isAnnotationPresent(org.springframework.amqp.rabbit.annotation.RabbitListener.class)) {
                return method;
            }
        }
        throw new AssertionError("未找到监听方法: " + name);
    }
}
