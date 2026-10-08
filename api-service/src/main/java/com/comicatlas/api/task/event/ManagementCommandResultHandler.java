package com.comicatlas.api.task.event;

import com.comicatlas.api.task.service.ManagementResultApplicationService;
import com.comicatlas.api.task.service.routing.ManagementResultRouter;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** 管理命令结果消息适配器：负责事件识别、消费确认和消息错误策略。 */
@Component
@RequiredArgsConstructor
public class ManagementCommandResultHandler {
    private final MqConsumerSupport mqConsumerSupport;
    private final ManagementResultRouter managementResultRouter;
    private final ManagementResultApplicationService managementResultApplicationService;

    @RabbitListener(queues = MqQueues.MANAGEMENT_RESULT)
    public void handleResult(ComicEvent raw, Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        if (raw instanceof MetadataRefreshScanCompletedEvent event) {
            mqConsumerSupport.consume(channel, tag, "元数据刷新完成: itemId=" + event.itemId(),
                    () -> managementResultRouter.routeMetadataRefreshCompleted(event));
            return;
        }
        if (raw instanceof ManagementCommandCompletedEvent
                || raw instanceof ManagementCommandFailedEvent
                || raw instanceof ManagementCommandProgressEvent
                || raw instanceof MediaUploadCompletedEvent) {
            mqConsumerSupport.consume(channel, tag, "管理命令结果: eventId=" + raw.eventId(),
                    () -> managementResultApplicationService.apply(raw));
            return;
        }
        mqConsumerSupport.consume(channel, tag, "管理命令未知事件: " + raw.eventId(), () -> { });
    }
}
