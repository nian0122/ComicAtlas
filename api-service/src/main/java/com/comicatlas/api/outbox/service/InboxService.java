package com.comicatlas.api.outbox.service;

/**
 * 结果 Inbox 服务。
 * <p>
 * 消费者在接收到结果事件后，通过 Inbox 保证业务逻辑恰好执行一次。
 * eventId PK + payloadHash 校验：同 eventId 同 payload 幂等跳过；同 eventId 不同 payload 隔离告警。
 */
public interface InboxService {

    /**
     * 检查事件是否已处理（幂等）。
     * <p>
     * 如果 eventId 不在 inbox 中，返回 false（未处理）。
     * 如果 eventId 在 inbox 中且 payloadHash 匹配，返回 true（已处理，幂等跳过）。
     * 如果 eventId 在 inbox 中但 payloadHash 不匹配，记录告警并返回 true（隔离跳过）。
     *
     * @param eventId     事件 UUID
     * @param payloadHash payload SHA-256
     * @return true 如果已处理过此事件（应跳过业务逻辑）
     */
    boolean isProcessed(String eventId, String payloadHash);

    /**
     * 记录事件处理完成。
     * <p>
     * 必须在业务逻辑的同一事务中调用，保证业务更新 + inbox 记录原子性。
     *
     * @param eventId     事件 UUID
     * @param payloadHash payload SHA-256
     */
    void markProcessed(String eventId, String payloadHash);

    /**
     * 记录事件处理完成（带 task/item 引用）。
     */
    void markProcessed(String eventId, String payloadHash, Long taskId, Long itemId, int attempt);
}
