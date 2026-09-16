package com.comicatlas.api.importer.event;

import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.importer.service.ImportResultService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportTaskFailedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 导入任务事件处理器（API 侧消费）。
 * <p>
 * 接收 MQ 消息并执行幂等/终态判断，导入成功后委托 {@link ImportPersistenceService} 完成两阶段落库。
 * catalog/chapter/media 持久化与最终化编排由 Service 负责；本类仅负责消息协议适配与消费策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MqConsumerSupport mqConsumerSupport;
    private final ImportPersistenceService importPersistenceService;
    private final ImportResultService importResultService;

    @RabbitListener(queues = MqQueues.IMPORT_RESULT)
    @SuppressWarnings("unchecked")
    public void handleComicImported(ImportTaskCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String idempKey = "mq:event:" + event.eventId();
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        mqConsumerSupport.consume(channel, tag, "导入完成: taskId=" + taskId, () -> {
            if (isEventProcessed(idempKey) || importResultService.isTerminal(taskId)) {
                log.info("事件已处理或任务已处终态，确认消息: eventId={}", event.eventId());
                markEventProcessed(idempKey);
                return;
            }

            Map<String, Object> metadata = importResultService.readMetadata(taskId);

            List<ImportPersistenceService.FinalizeRequest> requests =
                    importPersistenceService.persistCompleted(event, metadata);
            markEventProcessed(idempKey);

            log.info("ComicImported 完成: comicId={}, finalizeRequests={}", comicId, requests.size());
        });
    }

    @RabbitListener(queues = MqQueues.TASK_STATUS)
    public void handleTaskStatusChanged(TaskStatusChangedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        mqConsumerSupport.consume(channel, tag, "任务状态变更: taskId=" + event.taskId(), () -> {
            importResultService.applyStatus(event.taskId(), event.status(), event.progress(),
                    event.speedBytesPerSec(), event.etaSeconds(), event.downloadMethod(), event.errorMessage());
        });
    }

    @RabbitListener(queues = MqQueues.IMPORT_FAILED)
    public void handleImportTaskFailed(ImportTaskFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        log.warn("ImportTaskFailed: taskId={}, errorCode={}, message={}",
                taskId, event.errorCode(), event.errorMessage());

        mqConsumerSupport.consume(channel, tag, "导入失败: taskId=" + taskId, () -> {
            importResultService.applyFailed(taskId, event.errorCode(), event.errorMessage());
        });
    }

    private boolean isEventProcessed(String idempKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(idempKey));
        } catch (Exception e) {
            log.warn("幂等标记读取失败，降级使用 DB 状态判断: key={}", idempKey, e);
            return false;
        }
    }

    private void markEventProcessed(String idempKey) {
        try {
            redisTemplate.opsForValue().set(idempKey, "1", Duration.ofDays(1));
        } catch (Exception e) {
            log.warn("幂等标记写入失败: key={}", idempKey, e);
        }
    }
}
