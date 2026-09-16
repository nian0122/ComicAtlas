package com.comicatlas.api.media.event;

import com.comicatlas.api.media.service.VideoMetadataFixService;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 视频元数据修复完成事件处理器。
 * 接收 Worker 发来的 video.metadata.fix.completed 事件，更新 Media 的宽高和编码信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoMetadataFixCompletedHandler {

    private final VideoMetadataFixService videoMetadataFixService;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.VIDEO_METADATA_FIX_RESULT)
    public void handle(VideoMetadataFixCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        int total = event.results().size();
        log.info("视频元数据修复完成事件: comicId={}, total={}", comicId, total);

        mqConsumerSupport.consume(channel, tag, "视频元数据修复完成: comicId=" + comicId,
                () -> videoMetadataFixService.apply(event));
    }
}
