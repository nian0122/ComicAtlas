package com.comicatlas.api.exporter.event;

import com.comicatlas.api.exporter.service.ExportResultService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** 导出失败事件适配器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportFailedHandler {
    private final ExportResultService exportResultService;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.EXPORT_FAILED_RESULT)
    public void handle(ExportTaskFailedEvent event, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        log.info("导出失败事件: taskId={}, comicId={}, errorCode={}",
                event.taskId(), event.comicId(), event.errorCode());
        mqConsumerSupport.consume(channel, tag, "导出失败: taskId=" + event.taskId(),
                () -> exportResultService.applyFailed(event));
    }
}
