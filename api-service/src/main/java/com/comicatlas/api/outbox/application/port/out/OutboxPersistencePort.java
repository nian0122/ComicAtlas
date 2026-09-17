package com.comicatlas.api.outbox.application.port.out;

import java.time.LocalDateTime;

/** Outbox/Inbox 持久化输出端口。 */
public interface OutboxPersistencePort {
    void insertOutbox(OutboxCommand command);

    InboxSnapshot findInbox(String eventId);

    void insertInbox(InboxCommand command);

    long countPending();

    long countFailed();

    long countTotal();

    record OutboxCommand(String eventId, Long taskId, Long itemId, int attempt, String exchange,
                         String routingKey, String eventType, int version, String payload,
                         int publishAttempts, String status) {
    }

    record InboxSnapshot(String eventId, String payloadHash, Long taskId, Long itemId, int attempt,
                         LocalDateTime processedAt, LocalDateTime createdAt) {
    }

    record InboxCommand(String eventId, String payloadHash, Long taskId, Long itemId, int attempt,
                        LocalDateTime processedAt, LocalDateTime createdAt) {
    }
}
