package com.comicatlas.api.importer.event;

import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
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
 * 导入存储最终化事件 Handler 单元测试（TDD）。
 * <p>
 * Handler 只做协议适配：MQ 消费 → inbox 幂等 → 委托 Service；业务编排位于 Service。
 */
@ExtendWith(MockitoExtension.class)
class ImportStorageFinalizeHandlerTest {

    @Mock private ImportPersistenceService importPersistenceService;
    @Mock private InboxService inboxService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private Channel channel;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Spy private MqConsumerSupport mqConsumerSupport = new MqConsumerSupport();

    @InjectMocks private ImportStorageFinalizeEventHandler handler;

    private void runInTransaction() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("completed 事件：委托 Service.applyFinalizeCompleted 并标记 inbox")
    void handleFinalizeCompleted_delegatesToService_andMarksInbox() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);

        var event = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "hq/100/1001", 1);
        handler.handleFinalizeCompleted(event, channel, 1L);

        verify(importPersistenceService).applyFinalizeCompleted(event);
        verify(inboxService).markProcessed(eq(event.eventId().toString()), anyString());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("completed 重复事件：inbox 幂等跳过，不重复委托 Service")
    void handleFinalizeCompleted_duplicateEvent_skipsService() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(true);

        var event = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "hq/100/1001", 1);
        handler.handleFinalizeCompleted(event, channel, 1L);

        verify(importPersistenceService, never()).applyFinalizeCompleted(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("failed 事件：委托 Service.applyFinalizeFailed 并标记 inbox")
    void handleFinalizeFailed_delegatesToService_andMarksInbox() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(false);

        var event = new ImportStorageFinalizeFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L,
                "STORAGE_FINALIZE_SOURCE_MISSING", "源目录不存在");
        handler.handleFinalizeFailed(event, channel, 1L);

        verify(importPersistenceService).applyFinalizeFailed(event);
        verify(inboxService).markProcessed(eq(event.eventId().toString()), anyString());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("failed 重复事件：inbox 幂等跳过，不重复委托 Service")
    void handleFinalizeFailed_duplicateEvent_skipsService() throws Exception {
        runInTransaction();
        when(inboxService.isProcessed(anyString(), anyString())).thenReturn(true);

        var event = new ImportStorageFinalizeFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "X", "y");
        handler.handleFinalizeFailed(event, channel, 1L);

        verify(importPersistenceService, never()).applyFinalizeFailed(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("Handler 监听的队列常量与契约一致")
    void listenerQueues_matchFrozenContract() {
        verifyQueueMapping(handlerCompletedListener(), MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED);
        verifyQueueMapping(handlerFailedListener(), MqQueues.IMPORT_STORAGE_FINALIZE_FAILED);
    }

    private static void verifyQueueMapping(java.lang.reflect.Method listener, String expectedQueue) {
        org.springframework.amqp.rabbit.annotation.RabbitListener annotation =
                listener.getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assert annotation != null && expectedQueue.equals(annotation.queues()[0])
                : "监听队列与契约不符: expected=" + expectedQueue;
    }

    private static java.lang.reflect.Method handlerCompletedListener() {
        return findListener("handleFinalizeCompleted");
    }

    private static java.lang.reflect.Method handlerFailedListener() {
        return findListener("handleFinalizeFailed");
    }

    private static java.lang.reflect.Method findListener(String name) {
        for (java.lang.reflect.Method method : ImportStorageFinalizeEventHandler.class.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.isAnnotationPresent(org.springframework.amqp.rabbit.annotation.RabbitListener.class)) {
                return method;
            }
        }
        throw new AssertionError("未找到监听方法: " + name);
    }
}
