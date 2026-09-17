package com.comicatlas.api.outbox.application.service.impl;

import com.comicatlas.api.outbox.infrastructure.persistence.entity.OutboxMessage;
import com.comicatlas.api.outbox.domain.model.OutboxMessageStatus;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.api.outbox.application.port.out.OutboxPersistencePort;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Outbox 服务实现。
 * <p>
 * INSERT 参与当前事务，保证 DB commit 后 outbox 记录可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private static final int INITIAL_PUBLISH_ATTEMPTS = 0;
    private static final int MINIMUM_ATTEMPT = 0;

    private final OutboxPersistencePort outboxPersistencePort;
    private final ObjectMapper objectMapper;

    @Override
    public void enqueue(@NonNull ComicEvent event, @NonNull String exchange, @NonNull String routingKey) {
        enqueue(event, exchange, routingKey, null, null, MINIMUM_ATTEMPT);
    }

    @Override
    public void enqueue(@NonNull ComicEvent event, @NonNull String exchange, @NonNull String routingKey,
                        Long taskId, Long itemId, int attempt) {
        validateDestination(exchange, routingKey);
        if (attempt < MINIMUM_ATTEMPT) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Outbox attempt 不能为负数");
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            log.error("Outbox 序列化失败: eventId={}, eventType={}", event.eventId(), event.getClass().getSimpleName(), exception);
            throw new BusinessException("Outbox 序列化失败: " + event.eventId(), exception);
        }

        OutboxMessage outboxMessage = new OutboxMessage()
                .setEventId(event.eventId().toString())
                .setTaskId(taskId)
                .setItemId(itemId)
                .setAttempt(attempt)
                .setExchange(exchange)
                .setRoutingKey(routingKey)
                .setEventType(event.getClass().getSimpleName())
                .setVersion(event.version())
                .setPayload(payload)
                .setPublishAttempts(INITIAL_PUBLISH_ATTEMPTS)
                // 交由 MySQL CURRENT_TIMESTAMP 默认值，避免多实例 JVM 时钟偏差。
                .setStatus(OutboxMessageStatus.PENDING.name());

        outboxPersistencePort.insertOutbox(outboxMessage);
        log.debug("Outbox 写入: eventId={}, exchange={}, routingKey={}", event.eventId(), exchange, routingKey);
    }

    private void validateDestination(String exchange, String routingKey) {
        if (exchange.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Outbox exchange 不能为空");
        }
        if (routingKey.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Outbox routingKey 不能为空");
        }
    }
}
