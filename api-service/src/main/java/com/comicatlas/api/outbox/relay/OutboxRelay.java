package com.comicatlas.api.outbox.relay;

import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.common.event.ComicEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 消息中继。
 * <p>
 * 定时轮询 outbox_message 表，用 {@code FOR UPDATE SKIP LOCKED} 抢占待发布消息，
 * 通过 RabbitMQ publisher confirm 确认发布成功，失败时指数退避重试。
 * <p>
 * 支持多实例竞争（SKIP LOCKED），publish 成功前消息对其他实例不可见。
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxMessageMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /** 每次轮询抢注的最大消息数 */
    @Value("${outbox.relay.batch-size:50}")
    private int batchSize;

    /** 最大发布尝试次数（超过后标记 FAILED） */
    @Value("${outbox.relay.max-attempts:10}")
    private int maxAttempts;

    /** 退避基数（秒） */
    @Value("${outbox.relay.backoff-base:2}")
    private int backoffBase;

    /** 最大退避时间（秒） */
    @Value("${outbox.relay.backoff-max:60}")
    private int backoffMax;

    /** 是否启用定时调度（测试时可设为 false） */
    @Value("${outbox.relay.scheduled:true}")
    private boolean scheduledEnabled;

    public OutboxRelay(OutboxMessageMapper outboxMapper, RabbitTemplate rabbitTemplate,
                       TransactionTemplate transactionTemplate) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 定时轮询并中继消息。
     * 测试时设置 outbox.relay.scheduled=false 禁用调度。
     */
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:5000}")
    public void relayScheduled() {
        if (!scheduledEnabled) return;
        relay();
    }

    /**
     * 手动触发轮询（供测试和管理 API 调用）。
     */
    /**
     * 手动触发轮询（供测试和管理 API 调用）。
     */
    // 去掉 @Scheduled，独立方法供手动调用
    public void relay() {
        if (batchSize <= 0) return;

        log.info("OutboxRelay 开始轮询...");
        List<OutboxMessage> messages;
        try {
            messages = transactionTemplate.execute(status ->
                    outboxMapper.pollPending(batchSize));
        } catch (Exception e) {
            log.warn("Outbox 轮询异常: {}", e.getMessage());
            return;
        }

        if (messages == null || messages.isEmpty()) {
            log.debug("OutboxRelay 无待发送消息");
            return;
        }

        log.info("OutboxRelay 抢注 {} 条消息", messages.size());

        for (OutboxMessage msg : messages) {
            try {
                publishMessage(msg);
            } catch (Exception e) {
                log.error("Outbox 发布异常: eventId={}, error={}", msg.getEventId(), e.getMessage());
                handlePublishFailure(msg, e);
            }
        }
    }

    /**
     * 发布单条消息到 RabbitMQ。
     * <p>
     * 先同步发布（convertAndSend 返回即视为成功），然后通过异步 confirm 检测 nack。
     * 如果 convertAndSend 成功但 confirm 返回 nack，则将消息重置为 PENDING 等待重试。
     */
    private void publishMessage(OutboxMessage msg) {
        // 反序列化 payload 回 ComicEvent
        ComicEvent event;
        try {
            event = objectMapper.readValue(msg.getPayload(), ComicEvent.class);
        } catch (Exception e) {
            log.error("Outbox 反序列化失败: eventId={}, error={}", msg.getEventId(), e.getMessage());
            markFailed(msg, "反序列化失败: " + e.getMessage());
            return;
        }

        CorrelationData cd = new CorrelationData(msg.getEventId());

        // 异步 confirm：检测 nack，触发重试
        cd.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null) {
                log.warn("Outbox confirm 异常: eventId={}, error={}", msg.getEventId(), throwable.getMessage());
                resetForRetry(msg, throwable.getMessage());
            } else if (confirm != null && !confirm.isAck()) {
                String reason = confirm.getReason() != null ? confirm.getReason() : "nack";
                log.warn("Outbox 被 nack: eventId={}, reason={}", msg.getEventId(), reason);
                resetForRetry(msg, reason);
            }
        });

        try {
            rabbitTemplate.convertAndSend(msg.getExchange(), msg.getRoutingKey(), event, cd);
            // 同步发送成功 → 标记 PUBLISHED
            log.info("OutboxRelay 发布成功: eventId={}", msg.getEventId());
            handlePublishSuccess(msg);
        } catch (Exception e) {
            log.warn("Outbox 发送异常: eventId={}, error={}", msg.getEventId(), e.getMessage());
            handlePublishFailure(msg, e);
        }
    }

    /**
     * Confirm nack: 将已标记为 PUBLISHED 的消息重置为 PENDING 以便重试。
     * 使用 MySQL NOW() 计算 backoff，避免 JVM/DB 时钟偏差。
     */
    private void resetForRetry(OutboxMessage msg, String reason) {
        int nextAttempt = msg.getPublishAttempts() + 1;
        int backoffSecs = (int) Math.min((long) Math.pow(backoffBase, nextAttempt), backoffMax);
        try {
            outboxMapper.resetForRetryBySql(msg.getEventId(), nextAttempt, backoffSecs, reason);
        } catch (Exception e) {
            log.error("Outbox 重置失败: eventId={}", msg.getEventId(), e);
        }
    }

    /**
     * 发布成功：标记 PUBLISHED。
     */
    private void handlePublishSuccess(OutboxMessage msg) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxMessage update = new OutboxMessage()
                        .setEventId(msg.getEventId())
                        .setStatus("PUBLISHED")
                        .setPublishedAt(LocalDateTime.now());
                outboxMapper.updateById(update);
            });
            log.debug("Outbox 发布确认: eventId={}", msg.getEventId());
        } catch (Exception e) {
            log.error("Outbox 标记 PUBLISHED 失败: eventId={}", msg.getEventId(), e);
        }
    }

    /**
     * 发布失败：递增 attempt，计算退避时间（用 MySQL NOW() 避免时钟偏差），超出上限标记 FAILED。
     */
    private void handlePublishFailure(OutboxMessage msg, Throwable error) {
        int nextAttempt = msg.getPublishAttempts() + 1;
        String rawMsg = error != null ? error.getMessage() : "未知错误";
        final String errorMsg = (rawMsg != null && rawMsg.length() > 2000)
                ? rawMsg.substring(0, 2000) : rawMsg;

        if (nextAttempt >= maxAttempts) {
            markFailed(msg, errorMsg);
            return;
        }

        int backoffSecs = (int) Math.min((long) Math.pow(backoffBase, nextAttempt), backoffMax);

        try {
            outboxMapper.updateFailureBackoff(msg.getEventId(), nextAttempt, backoffSecs, errorMsg);
            log.info("Outbox 发布失败，将在 {} 秒后重试: eventId={}, attempt={}/{}",
                    backoffSecs, msg.getEventId(), nextAttempt, maxAttempts);
        } catch (Exception e) {
            log.error("Outbox 更新重试信息失败: eventId={}", msg.getEventId(), e);
        }
    }

    private void markFailed(OutboxMessage msg, String errorMsg) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxMessage update = new OutboxMessage()
                        .setEventId(msg.getEventId())
                        .setStatus("FAILED")
                        .setLastError(errorMsg)
                        .setPublishAttempts(msg.getPublishAttempts() + 1);
                outboxMapper.updateById(update);
            });
            log.error("Outbox 发布彻底失败: eventId={}, attempts={}, error={}",
                    msg.getEventId(), msg.getPublishAttempts() + 1, errorMsg);
        } catch (Exception e) {
            log.error("Outbox 标记 FAILED 失败: eventId={}", msg.getEventId(), e);
        }
    }
}
