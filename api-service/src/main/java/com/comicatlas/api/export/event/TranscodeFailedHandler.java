package com.comicatlas.api.export.event;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.enums.TranscodeStatus;
import com.comicatlas.common.event.VideoTranscodeFailedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
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
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.VIDEO_TRANSCODE_FAILED)
    public void handleFailed(VideoTranscodeFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        mqConsumerSupport.consume(channel, tag, "转码失败: pageId=" + event.pageId(), () -> {
            Media media = mediaMapper.selectById(event.pageId());
            if (media == null || media.getTranscodeStatus() == null
                    || !media.getTranscodeStatus().isProcessing()) {
                log.warn("TranscodeFailed: page not in processing, skip. pageId={}", event.pageId());
                return;
            }
            media.setTranscodeStatus(TranscodeStatus.FAILED);
            mediaMapper.updateById(media);
            log.warn("TranscodeFailed: pageId={}, error={}", event.pageId(), event.errorMessage());
        });
    }
}
