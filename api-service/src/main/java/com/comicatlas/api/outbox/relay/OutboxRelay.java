package com.comicatlas.api.outbox.relay;

import com.comicatlas.api.outbox.persistence.entity.OutboxMessage;
import com.comicatlas.api.outbox.enums.OutboxMessageStatus;
import com.comicatlas.api.outbox.persistence.mapper.OutboxMessageMapper;
import com.comicatlas.common.event.ComicEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final OutboxMessageMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;

    /** 独立配置的 ObjectMapper：outbox payload 反序列化需 JavaTimeModule，不注入 Spring 默认实例 */
    private final ObjectMapper objectMapper = createOutboxObjectMapper();

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

    private static ObjectMapper createOutboxObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * 定时轮询并中继消息。
     * 测试时设置 outbox.relay.scheduled=false 禁用调度。
     */
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:5000}")
    public void relayScheduled() {
        if (!scheduledEnabled) { return; }
        relay();
    }

    /**
     * 手动触发轮询（供测试和管理 API 调用）。
     */
    public void relay() {
        if (batchSize <= 0) { return; }

        log.debug("OutboxRelay 开始轮询...");
        List<OutboxMessage> messages;
        try {
            messages = transactionTemplate.execute(status ->
                    outboxMapper.pollPending(batchSize));
        } catch (Exception exception) {
            log.warn("Outbox 轮询异常", exception);
            return;
        }

        if (messages == null || messages.isEmpty()) {
            log.debug("OutboxRelay 无待发送消息");
            return;
        }

        log.info("OutboxRelay 抢注 {} 条消息", messages.size());

        for (OutboxMessage outboxMessage : messages) {
            try {
                publishMessage(outboxMessage);
            } catch (Exception exception) {
                log.error("Outbox 发布异常: eventId={}, error={}", outboxMessage.getEventId(), exception.getMessage(), exception);
                handlePublishFailure(outboxMessage, exception);
            }
        }
    }

    /**
     * 发布单条消息到 RabbitMQ。
     * <p>
     * 先提交发布，再以 publisher confirm 作为成功判据；confirm nack/异常时重置为 PENDING。
     */
    private void publishMessage(OutboxMessage outboxMessage) {
        // 反序列化 payload 回 ComicEvent
        ComicEvent event;
        try {
            event = objectMapper.readValue(outboxMessage.getPayload(), ComicEvent.class);
        } catch (Exception exception) {
            log.error("Outbox 反序列化失败: eventId={}, error={}", outboxMessage.getEventId(), exception.getMessage(), exception);
            markFailed(outboxMessage, "反序列化失败: " + exception.getMessage());
            return;
        }

        CorrelationData correlationData = new CorrelationData(outboxMessage.getEventId());

        // 异步 confirm：检测 nack，触发重试
        correlationData.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null) {
                log.warn("Outbox confirm 异常: eventId={}, error={}", outboxMessage.getEventId(), throwable.getMessage(), throwable);
                resetForRetry(outboxMessage, throwable.getMessage());
            } else if (confirm != null && !confirm.isAck()) {
                String reason = confirm.getReason() != null ? confirm.getReason() : "nack";
                log.warn("Outbox 被 nack: eventId={}, reason={}", outboxMessage.getEventId(), reason);
                resetForRetry(outboxMessage, reason);
            } else if (confirm != null) {
                // 只有 broker confirm 成功后才能标记 PUBLISHED，避免发送线程与 nack 回调竞态。
                handlePublishSuccess(outboxMessage);
            }
        });

        try {
            rabbitTemplate.convertAndSend(outboxMessage.getExchange(), outboxMessage.getRoutingKey(), event, correlationData);
            // convertAndSend 成功只表示已提交到客户端，最终状态等待 broker confirm 回调。
            log.debug("OutboxRelay 已提交发布，等待 broker confirm: eventId={}", outboxMessage.getEventId());
        } catch (Exception exception) {
            log.warn("Outbox 发送异常: eventId={}, error={}", outboxMessage.getEventId(), exception.getMessage(), exception);
            handlePublishFailure(outboxMessage, exception);
        }
    }

    /**
     * Confirm nack 或异常：在重试上限内退避，达到上限后标记 FAILED。
     * 使用 MySQL NOW() 计算 backoff，避免 JVM/DB 时钟偏差。
     */
    private void resetForRetry(OutboxMessage outboxMessage, String reason) {
        String errorMessage = truncateErrorMessage(reason);
        int nextAttempt = outboxMessage.getPublishAttempts() + 1;
        if (nextAttempt >= maxAttempts) {
            markFailed(outboxMessage, errorMessage);
            return;
        }
        int backoffSeconds = (int) Math.min((long) Math.pow(backoffBase, nextAttempt), backoffMax);
        try {
            outboxMapper.resetForRetryBySql(outboxMessage.getEventId(), nextAttempt, backoffSeconds, errorMessage);
        } catch (Exception exception) {
            log.error("Outbox 重置失败: eventId={}", outboxMessage.getEventId(), exception);
        }
    }

    /**
     * 发布成功：标记 PUBLISHED。
     */
    private void handlePublishSuccess(OutboxMessage outboxMessage) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxMessage update = new OutboxMessage()
                        .setEventId(outboxMessage.getEventId())
                        .setStatus(OutboxMessageStatus.PUBLISHED.name())
                        .setPublishedAt(LocalDateTime.now());
                outboxMapper.updateById(update);
            });
            log.debug("Outbox 发布确认: eventId={}", outboxMessage.getEventId());
        } catch (Exception exception) {
            log.error("Outbox 标记 PUBLISHED 失败: eventId={}", outboxMessage.getEventId(), exception);
        }
    }

    /**
     * 发布失败：递增 attempt，计算退避时间（用 MySQL NOW() 避免时钟偏差），超出上限标记 FAILED。
     */
    private void handlePublishFailure(OutboxMessage outboxMessage, Throwable error) {
        int nextAttempt = outboxMessage.getPublishAttempts() + 1;
        String rawErrorMessage = error != null ? error.getMessage() : "未知错误";
        String errorMessage = truncateErrorMessage(rawErrorMessage);

        if (nextAttempt >= maxAttempts) {
            markFailed(outboxMessage, errorMessage);
            return;
        }

        int backoffSeconds = (int) Math.min((long) Math.pow(backoffBase, nextAttempt), backoffMax);

        try {
            outboxMapper.updateFailureBackoff(outboxMessage.getEventId(), nextAttempt, backoffSeconds, errorMessage);
            log.info("Outbox 发布失败，将在 {} 秒后重试: eventId={}, attempt={}/{}",
                    backoffSeconds, outboxMessage.getEventId(), nextAttempt, maxAttempts);
        } catch (Exception exception) {
            log.error("Outbox 更新重试信息失败: eventId={}", outboxMessage.getEventId(), exception);
        }
    }

    private String truncateErrorMessage(String errorMessage) {
        return errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH
                ? errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH) : errorMessage;
    }

    private void markFailed(OutboxMessage outboxMessage, String errorMessage) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OutboxMessage update = new OutboxMessage()
                        .setEventId(outboxMessage.getEventId())
                        .setStatus(OutboxMessageStatus.FAILED.name())
                        .setLastError(errorMessage)
                        .setPublishAttempts(outboxMessage.getPublishAttempts() + 1);
                outboxMapper.updateById(update);
            });
            log.error("Outbox 发布彻底失败: eventId={}, attempts={}, error={}",
                    outboxMessage.getEventId(), outboxMessage.getPublishAttempts() + 1, errorMessage);
        } catch (Exception exception) {
            log.error("Outbox 标记 FAILED 失败: eventId={}", outboxMessage.getEventId(), exception);
        }
    }
}
