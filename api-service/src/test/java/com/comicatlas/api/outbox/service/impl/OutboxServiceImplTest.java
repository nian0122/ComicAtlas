package com.comicatlas.api.outbox.application.service.impl;

import com.comicatlas.api.outbox.application.port.out.OutboxPersistencePort;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.contract.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceImplTest {

    @Mock
    private OutboxPersistencePort outboxPersistencePort;

    private OutboxServiceImpl outboxService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxServiceImpl(outboxPersistencePort, objectMapper);
    }

    @Test
    void enqueue_writesPendingMessage() {
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), 1L, 2L, "ZIP", "source");

        outboxService.enqueue(event, "comic.import", "task.created", 3L, 4L, 2);

        ArgumentCaptor<OutboxPersistencePort.OutboxCommand> messageCaptor =
                ArgumentCaptor.forClass(OutboxPersistencePort.OutboxCommand.class);
        verify(outboxPersistencePort).insertOutbox(messageCaptor.capture());
        OutboxPersistencePort.OutboxCommand message = messageCaptor.getValue();
        assertThat(message.eventId()).isEqualTo(event.eventId().toString());
        assertThat(message.status()).isEqualTo("PENDING");
        assertThat(message.payload()).contains("ImportTaskCreatedEvent");
        assertThat(message.taskId()).isEqualTo(3L);
        assertThat(message.itemId()).isEqualTo(4L);
        assertThat(message.attempt()).isEqualTo(2);
    }

    @Test
    void enqueue_rejectsBlankDestination() {
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), 1L, 2L, "ZIP", "source");

        assertThatThrownBy(() -> outboxService.enqueue(event, " ", "task.created"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Outbox exchange 不能为空");
        assertThatThrownBy(() -> outboxService.enqueue(event, "comic.import", " "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Outbox routingKey 不能为空");
    }

    @Test
    void enqueue_rejectsNegativeAttempt() {
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), 1L, 2L, "ZIP", "source");

        assertThatThrownBy(() -> outboxService.enqueue(event, "comic.import", "task.created", null, null, -1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Outbox attempt 不能为负数");
    }
}
