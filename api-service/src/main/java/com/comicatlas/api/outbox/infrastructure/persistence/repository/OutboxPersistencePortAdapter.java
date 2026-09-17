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
    public void insertOutbox(OutboxMessage outboxMessage) {
        outboxMessageMapper.insert(outboxMessage);
    }

    @Override
    public InboxReceipt findInbox(String eventId) {
        return inboxReceiptMapper.selectById(eventId);
    }

    @Override
    public void insertInbox(InboxReceipt inboxReceipt) {
        inboxReceiptMapper.insert(inboxReceipt);
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
