package com.comicatlas.api.export.event;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.event.VideoTranscodeCompletedEvent;
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

    @RabbitListener(queues = "video.transcode.completed.queue")
    public void handleCompleted(VideoTranscodeCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        try {
            Media media = mediaMapper.selectById(event.pageId());
            if (media == null || !"PENDING".equals(media.getTranscodeStatus())) {
                log.warn("TranscodeCompleted: page not in PENDING, skip. pageId={}", event.pageId());
                channel.basicAck(tag, false);
                return;
            }
            media.setHqPath(event.newHqPath());
            media.setContainer(event.container());
            media.setVideoCodec(event.videoCodec());
            media.setAudioCodec(event.audioCodec());
            media.setFileSize(event.fileSize());
            media.setTranscodeStatus("DONE");
            mediaMapper.updateById(media);
            channel.basicAck(tag, false);
            log.info("TranscodeCompleted: pageId={}, newPath={}", event.pageId(), event.newHqPath());
        } catch (Exception e) {
            log.error("TranscodeCompleted failed: pageId={}", event.pageId(), e);
            try { channel.basicReject(tag, false); } catch (Exception ex) { log.warn("消息 reject 失败: tag={}", tag, ex); }
        }
    }
}
