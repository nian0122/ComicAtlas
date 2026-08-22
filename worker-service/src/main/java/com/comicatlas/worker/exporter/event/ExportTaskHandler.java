package com.comicatlas.worker.exporter.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.exporter.ExportService;
import com.comicatlas.worker.exporter.ExportEventPublisher;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;


/**
 * 导出任务 MQ 消费者 — 只负责协议与事件发布，业务编排委托 ExportService。
 *
 * <p>ACK/REQUEUE 语义：构建/校验失败时发布 failed 事件后正常 ACK（失败事件即业务结果）；
 * completed 事件发布失败时不发布 failed，抛出使消息 requeue 重投；重投经发布器幂等复用
 * 既有任务目录后重新发布 completed。
 */
@Slf4j
@Component
public class ExportTaskHandler {

    private final ExportService exportService;
    private final ExportEventPublisher eventPublisher;
    private final MqConsumerSupport mqConsumerSupport;

    @Autowired
    public ExportTaskHandler(ExportService exportService, ExportEventPublisher eventPublisher,
                             MqConsumerSupport mqConsumerSupport) {
        this.exportService = exportService;
        this.eventPublisher = eventPublisher;
        this.mqConsumerSupport = mqConsumerSupport;
    }

    ExportTaskHandler(org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate,
                      ExportService exportService, MqConsumerSupport mqConsumerSupport) {
        this(exportService, new ExportEventPublisher(rabbitTemplate), mqConsumerSupport);
    }

    @RabbitListener(queues = MqQueues.EXPORT_TASK)
    public void handle(ExportTaskCreatedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出任务开始: taskId={}, comicId={}", taskId, comicId);
        mqConsumerSupport.consume(channel, tag, "导出任务: taskId=" + taskId,
                () -> exportAndPublish(event),
                null,
                MqConsumerSupport.FailurePolicy.REQUEUE);
    }

    private void exportAndPublish(ExportTaskCreatedEvent event) throws Exception {
        eventPublisher.publishStarted(event.taskId(), event.comicId());
        log.info("已发布 ExportTaskStartedEvent: taskId={}", event.taskId());

        ExportService.ExportOutput output;
        try {
            output = ExportFormats.CBZ.equalsIgnoreCase(event.format())
                    ? exportService.export(event.comicId(), event.taskId(), ExportFormats.CBZ)
                    : exportService.export(event.comicId(), event.taskId());
        } catch (Exception e) {
            publishExportFailed(event, e);
            return;
        }
        try {
            eventPublisher.publishCompleted(event.taskId(), event.comicId(), output);
            log.info("已发布 ExportTaskCompletedEvent: taskId={}, size={}", event.taskId(), output.size());
        } catch (Exception e) {
            throw new ExportCompletedPublishException(
                    "导出完成事件发布失败：taskId=" + event.taskId() + ", comicId=" + event.comicId(), e);
        }
    }

    private void publishExportFailed(ExportTaskCreatedEvent event, Exception failure) {
        String errorCode = exportService.classifyExportError(failure);
        eventPublisher.publishFailed(event.taskId(), event.comicId(), errorCode, failure.getMessage());
    }

    /** completed 事件发布失败标记：不发布 failed，抛出使消息 requeue 重投。 */
    private static class ExportCompletedPublishException extends Exception {
        ExportCompletedPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
