package com.comicatlas.api.importer.event;

import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ImportMetadataRefreshCompletedEvent;
import com.comicatlas.common.event.ImportMetadataRefreshFailedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 导入最终化元数据重建结果事件处理器（Worker → API，协议适配）。
 * <p>
 * 只做协议适配：MQ 消费 → inbox/eventId 幂等去重 → 委托 {@link ImportPersistenceService}；
 * 业务编排（README 收尾/失败重试条件/统计/缓存）位于 Service。
 * <p>
 * 幂等：inbox 以 eventId 为 PK，同 eventId 同 payload 只处理一次；重复/乱序/过期事件静默跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportMetadataRefreshResultHandler {

    private final ImportPersistenceService importPersistenceService;
    private final InboxService inboxService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.IMPORT_METADATA_REFRESH_COMPLETED)
    public void handleMetadataRefreshCompleted(ImportMetadataRefreshCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String label = "导入元数据重建完成: taskId=" + event.taskId() + ", comicId=" + event.comicId();
        log.info("ImportMetadataRefreshResultHandler: {}", label);
        mqConsumerSupport.consume(channel, tag, label, () -> processEvent(event));
    }

    @RabbitListener(queues = MqQueues.IMPORT_METADATA_REFRESH_FAILED)
    public void handleMetadataRefreshFailed(ImportMetadataRefreshFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String label = "导入元数据重建失败: taskId=" + event.taskId() + ", comicId=" + event.comicId();
        log.warn("ImportMetadataRefreshResultHandler: {}", label);
        mqConsumerSupport.consume(channel, tag, label, () -> processEvent(event));
    }

    /** inbox 幂等 + 委托 Service（同一事务：业务更新 + inbox 记录原子性）。 */
    private void processEvent(ComicEvent event) {
        String eventId = event.eventId().toString();
        String payloadHash = sha256(toJson(event));
        transactionTemplate.executeWithoutResult(tx -> {
            if (inboxService.isProcessed(eventId, payloadHash)) {
                log.debug("Inbox 幂等跳过元数据重建结果事件: eventId={}", eventId);
                return;
            }
            if (event instanceof ImportMetadataRefreshCompletedEvent completed) {
                importPersistenceService.applyMetadataRefreshCompleted(completed);
            } else if (event instanceof ImportMetadataRefreshFailedEvent failed) {
                importPersistenceService.applyMetadataRefreshFailed(failed);
            }
            inboxService.markProcessed(eventId, payloadHash);
        });
    }

    private String toJson(ComicEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("元数据重建结果事件序列化失败: " + event.eventId(), e);
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
