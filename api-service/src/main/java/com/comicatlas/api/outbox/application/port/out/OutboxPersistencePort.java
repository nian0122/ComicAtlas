package com.comicatlas.api.outbox.application.port.out;

import com.comicatlas.api.outbox.infrastructure.persistence.entity.InboxReceipt;
import com.comicatlas.api.outbox.infrastructure.persistence.entity.OutboxMessage;

/** Outbox/Inbox 持久化输出端口。 */
public interface OutboxPersistencePort {
    void insertOutbox(OutboxMessage outboxMessage);

    InboxReceipt findInbox(String eventId);

    void insertInbox(InboxReceipt inboxReceipt);

    long countPending();

    long countFailed();

    long countTotal();
}
