package com.comicatlas.api.export.event;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.event.VideoTranscodeFailedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeFailedHandler {

    private final MediaMapper mediaMapper;

    @RabbitListener(queues = "video.transcode.result.queue")
    public void handleFailed(VideoTranscodeFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        try {
            Media media = mediaMapper.selectById(event.pageId());
            if (media == null || !"PROCESSING".equals(media.getTranscodeStatus())) {
                channel.basicAck(tag, false);
                return;
            }
            media.setTranscodeStatus("FAILED");
            mediaMapper.updateById(media);
            channel.basicAck(tag, false);
            log.warn("TranscodeFailed: pageId={}, error={}", event.pageId(), event.errorMessage());
        } catch (Exception e) {
            log.error("TranscodeFailed handler error: pageId={}", event.pageId(), e);
            try { channel.basicReject(tag, false); } catch (Exception ignored) {}
        }
    }
}
