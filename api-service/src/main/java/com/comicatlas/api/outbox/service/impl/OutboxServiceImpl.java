package com.comicatlas.api.outbox.service.impl;

import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.event.ComicEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Outbox 服务实现。
 * <p>
 * INSERT 参与当前事务，保证 DB commit 后 outbox 记录可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxMessageMapper outboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void init() {
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void enqueue(@NonNull ComicEvent event, @NonNull String exchange, @NonNull String routingKey) {
        enqueue(event, exchange, routingKey, null, null, 0);
    }

    @Override
    public void enqueue(@NonNull ComicEvent event, @NonNull String exchange, @NonNull String routingKey,
                        Long taskId, Long itemId, int attempt) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Outbox 序列化失败: eventId={}, eventType={}", event.eventId(), event.getClass().getSimpleName(), e);
            throw new RuntimeException("Outbox 序列化失败: " + event.eventId(), e);
        }

        OutboxMessage msg = new OutboxMessage()
                .setEventId(event.eventId().toString())
                .setTaskId(taskId)
                .setItemId(itemId)
                .setAttempt(attempt)
                .setExchange(exchange)
                .setRoutingKey(routingKey)
                .setEventType(event.getClass().getSimpleName())
                .setVersion(event.version())
                .setPayload(payload)
                .setPublishAttempts(0)
                .setStatus("PENDING")
                .setAvailableAt(null) // 交由 MySQL CURRENT_TIMESTAMP 默认值，避免时钟偏差
                .setCreatedAt(LocalDateTime.now());

        outboxMapper.insert(msg);
        log.debug("Outbox 写入: eventId={}, exchange={}, routingKey={}", event.eventId(), exchange, routingKey);
    }
}
