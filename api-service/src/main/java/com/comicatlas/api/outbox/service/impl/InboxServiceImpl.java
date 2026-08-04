package com.comicatlas.api.outbox.service.impl;

import com.comicatlas.api.outbox.entity.InboxReceipt;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.service.InboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Inbox 服务实现。
 * <p>
 * 通过 eventId PK + payloadHash 校验保证恰好一次处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxServiceImpl implements InboxService {

    private final InboxReceiptMapper inboxMapper;

    @Override
    public boolean isProcessed(String eventId, String payloadHash) {
        InboxReceipt existing = inboxMapper.selectById(eventId);
        if (existing == null) {
            return false; // 未处理
        }

        if (existing.getPayloadHash().equals(payloadHash)) {
            log.debug("Inbox 幂等跳过: eventId={}, payloadHash={}", eventId, payloadHash);
            return true; // 同 eventId 同 payload，已处理
        }

        // 同 eventId 不同 payload：隔离告警
        log.warn("Inbox payload hash 冲突: eventId={}, existingHash={}, incomingHash={}",
                eventId, existing.getPayloadHash(), payloadHash);
        return true; // 返回 true 阻止处理（隔离）
    }

    @Override
    public void markProcessed(String eventId, String payloadHash) {
        markProcessed(eventId, payloadHash, null, null, 0);
    }

    @Override
    public void markProcessed(String eventId, String payloadHash, Long taskId, Long itemId, int attempt) {
        InboxReceipt receipt = new InboxReceipt()
                .setEventId(eventId)
                .setPayloadHash(payloadHash)
                .setTaskId(taskId)
                .setItemId(itemId)
                .setAttempt(attempt)
                .setProcessedAt(LocalDateTime.now())
                .setCreatedAt(LocalDateTime.now());

        inboxMapper.insert(receipt);
        log.debug("Inbox 记录: eventId={}", eventId);
    }
}
