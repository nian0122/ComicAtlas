package com.comicatlas.api.exporter.interfaces.messaging;

import com.comicatlas.api.exporter.application.port.in.ExportResultService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** 导出完成事件适配器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportCompletedHandler {
    private final ExportResultService exportResultService;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.EXPORT_COMPLETED_RESULT)
    public void handle(ExportTaskCompletedEvent event, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        log.info("导出完成事件: taskId={}, comicId={}, outputSize={}",
                event.taskId(), event.comicId(), event.outputSize());
        mqConsumerSupport.consume(channel, tag, "导出完成: taskId=" + event.taskId(),
                () -> exportResultService.applyCompleted(event));
    }
}
