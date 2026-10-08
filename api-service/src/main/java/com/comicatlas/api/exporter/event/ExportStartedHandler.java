package com.comicatlas.api.exporter.event;

import com.comicatlas.api.exporter.service.ExportResultService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 导出启动事件处理器。
 * 接收 Worker 发来的 task.started 事件，更新 ExportTask 状态为 RUNNING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportStartedHandler {

    private final ExportResultService exportResultService;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.EXPORT_STARTED_RESULT)
    public void handle(ExportTaskStartedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("导出启动事件: taskId={}, comicId={}", taskId, comicId);

        mqConsumerSupport.consume(channel, tag, "导出启动: taskId=" + taskId,
                () -> exportResultService.applyStarted(event));
    }
}
