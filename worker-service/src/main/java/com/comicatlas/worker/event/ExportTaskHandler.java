package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.export.ExportService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 导出任务 MQ 消费者 — 只负责协议与事件发布，业务编排委托 ExportService。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskHandler {

    private final RabbitTemplate rabbitTemplate;
    private final ExportService exportService;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.EXPORT_TASK)
    public void handle(ExportTaskCreatedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出任务开始: taskId={}, comicId={}", taskId, comicId);
        mqConsumerSupport.consume(channel, tag, "导出任务: taskId=" + taskId,
                () -> exportAndPublish(event),
                e -> publishExportFailed(event, e),
                MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
    }

    private void exportAndPublish(ExportTaskCreatedEvent event) throws Exception {
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_STARTED,
                new ExportTaskStartedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId()));
        log.info("已发布 ExportTaskStartedEvent: taskId={}", event.taskId());

        ExportService.ExportOutput output = exportService.export(event.comicId(), event.taskId());

        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_COMPLETED,
                new ExportTaskCompletedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId(), "EXPORT",
                        output.fileName(), output.size()));
        log.info("已发布 ExportTaskCompletedEvent: taskId={}, size={}", event.taskId(), output.size());
    }

    private void publishExportFailed(ExportTaskCreatedEvent event, Exception failure) {
        String errorCode = exportService.classifyExportError(failure);
        rabbitTemplate.convertAndSend(MqExchanges.EXPORT, MqRoutingKeys.TASK_FAILED,
                new ExportTaskFailedEvent(UUID.randomUUID(), Instant.now(),
                        event.taskId(), event.comicId(), errorCode, failure.getMessage()));
    }
}
