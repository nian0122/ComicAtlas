package com.comicatlas.api.outbox.infrastructure.persistence.repository;

import com.comicatlas.api.outbox.application.port.out.OutboxPersistencePort;
import com.comicatlas.api.outbox.infrastructure.persistence.entity.InboxReceipt;
import com.comicatlas.api.outbox.infrastructure.persistence.entity.OutboxMessage;
import com.comicatlas.api.outbox.infrastructure.persistence.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.infrastructure.persistence.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Outbox/Inbox 输出端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class OutboxPersistencePortAdapter implements OutboxPersistencePort {
    private final OutboxMessageMapper outboxMessageMapper;
    private final InboxReceiptMapper inboxReceiptMapper;

    @Override
    public void insertOutbox(OutboxPersistencePort.OutboxCommand command) {
        OutboxMessage message = new OutboxMessage()
                .setEventId(command.eventId()).setTaskId(command.taskId()).setItemId(command.itemId())
                .setAttempt(command.attempt()).setExchange(command.exchange()).setRoutingKey(command.routingKey())
                .setEventType(command.eventType()).setVersion(command.version()).setPayload(command.payload())
                .setPublishAttempts(command.publishAttempts()).setStatus(command.status());
        outboxMessageMapper.insert(message);
    }

    @Override
    public OutboxPersistencePort.InboxSnapshot findInbox(String eventId) {
        InboxReceipt receipt = inboxReceiptMapper.selectById(eventId);
        return receipt == null ? null : new OutboxPersistencePort.InboxSnapshot(receipt.getEventId(),
                receipt.getPayloadHash(), receipt.getTaskId(), receipt.getItemId(), receipt.getAttempt(),
                receipt.getProcessedAt(), receipt.getCreatedAt());
    }

    @Override
    public void insertInbox(OutboxPersistencePort.InboxCommand command) {
        InboxReceipt receipt = new InboxReceipt().setEventId(command.eventId()).setPayloadHash(command.payloadHash())
                .setTaskId(command.taskId()).setItemId(command.itemId()).setAttempt(command.attempt())
                .setProcessedAt(command.processedAt()).setCreatedAt(command.createdAt());
        inboxReceiptMapper.insert(receipt);
    }

    @Override
    public long countPending() {
        return outboxMessageMapper.countPending();
    }

    @Override
    public long countFailed() {
        return outboxMessageMapper.countFailed();
    }

    @Override
    public long countTotal() {
        return outboxMessageMapper.selectCount(null);
    }
}
