package com.comicatlas.api.outbox.infrastructure.relay;

import com.comicatlas.api.outbox.infrastructure.persistence.entity.OutboxMessage;
import com.comicatlas.api.outbox.infrastructure.persistence.mapper.OutboxMessageMapper;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {
    @Mock private OutboxMessageMapper outboxMapper;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private TransactionTemplate transactionTemplate;

    private OutboxRelay relay;
    private OutboxMessage message;

    @BeforeEach
    void setUp() throws Exception {
        relay = new OutboxRelay(outboxMapper, rabbitTemplate, transactionTemplate);
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        ReflectionTestUtils.setField(relay, "maxAttempts", 3);
        ReflectionTestUtils.setField(relay, "backoffBase", 2);
        ReflectionTestUtils.setField(relay, "backoffMax", 60);
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), 1L, 2L, "ZIP", "source");
        message = new OutboxMessage().setEventId(event.eventId().toString())
                .setExchange("comic.import").setRoutingKey("task.created")
                .setPublishAttempts(0).setPayload(new ObjectMapper()
                        .registerModule(new JavaTimeModule()).writeValueAsString(event));
        when(outboxMapper.pollPending(10)).thenReturn(List.of(message));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
    }

    @Test
    void nackBelowLimitSchedulesRetryAndTruncatesError() {
        completeConfirm(false, "错".repeat(2100));
        relay.relay();
        verify(outboxMapper).resetForRetryBySql(message.getEventId(), 1, 2, "错".repeat(2000));
        verify(outboxMapper, never()).updateById(any(OutboxMessage.class));
    }

    @Test
    void nackAtLimitMarksMessageFailed() {
        message.setPublishAttempts(2);
        allowStatusUpdate();
        completeConfirm(false, "拒绝发布");
        relay.relay();
        assertFailedAtLimit();
    }

    @Test
    void exceptionalConfirmAtLimitMarksMessageFailed() {
        message.setPublishAttempts(2);
        allowStatusUpdate();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().completeExceptionally(new IllegalStateException("连接中断"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
        relay.relay();
        assertFailedAtLimit();
    }

    @Test
    void ackMarksMessagePublished() {
        allowStatusUpdate();
        completeConfirm(true, null);
        relay.relay();
        ArgumentCaptor<OutboxMessage> updateCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("PUBLISHED");
        assertThat(updateCaptor.getValue().getPublishedAt()).isNotNull();
    }

    private void assertFailedAtLimit() {
        ArgumentCaptor<OutboxMessage> updateCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(updateCaptor.getValue().getPublishAttempts()).isEqualTo(3);
        verify(outboxMapper, never()).resetForRetryBySql(anyString(), anyInt(), anyInt(), anyString());
    }

    private void allowStatusUpdate() {
        doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private void completeConfirm(boolean isAck, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(isAck, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }
}
