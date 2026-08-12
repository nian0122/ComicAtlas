package com.comicatlas.api.export.event;

import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.common.event.VideoTranscodeCompletedEvent;
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
public class TranscodeCompletedHandler {

    private final MediaMapper mediaMapper;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.VIDEO_TRANSCODE_COMPLETED)
    public void handleCompleted(VideoTranscodeCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        mqConsumerSupport.consume(channel, tag, "转码完成: pageId=" + event.pageId(), () -> {
            Media media = mediaMapper.selectById(event.pageId());
            if (media == null || media.getTranscodeStatus() == null
                    || !media.getTranscodeStatus().isProcessing()) {
                log.warn("TranscodeCompleted: page not in processing, skip. pageId={}", event.pageId());
                return;
            }
            media.setHqPath(event.newHqPath());
            media.setContainer(event.container());
            media.setVideoCodec(event.videoCodec());
            media.setAudioCodec(event.audioCodec());
            media.setFileSize(event.fileSize());
            media.setTranscodeStatus(TranscodeStatus.READY);
            mediaMapper.updateById(media);
            log.info("TranscodeCompleted: pageId={}, newPath={}", event.pageId(), event.newHqPath());
        });
    }
}
