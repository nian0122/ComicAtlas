package com.comicatlas.api.admin.event;

import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;
import com.comicatlas.common.event.VideoMetadataFixResult;
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

    private final MediaMapper mediaMapper;

    @RabbitListener(queues = MqQueues.VIDEO_METADATA_FIX_RESULT)
    public void handle(VideoMetadataFixCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        int total = event.results().size();
        int fixed = 0;
        log.info("视频元数据修复完成事件: comicId={}, total={}", comicId, total);

        try {
            for (VideoMetadataFixResult result : event.results()) {
                Media media = mediaMapper.selectById(result.pageId());
                if (media == null) {
                    log.warn("Media 不存在: pageId={}", result.pageId());
                    continue;
                }
                if (result.width() != null) { media.setWidth(result.width()); }
                if (result.height() != null) { media.setHeight(result.height()); }
                if (result.duration() != null) { media.setDuration(result.duration()); }
                if (result.container() != null) { media.setContainer(result.container()); }
                if (result.videoCodec() != null) { media.setVideoCodec(result.videoCodec()); }
                if (result.audioCodec() != null) { media.setAudioCodec(result.audioCodec()); }
                mediaMapper.updateById(media);
                fixed++;
            }

            channel.basicAck(tag, false);
            log.info("视频元数据修复完成: comicId={}, total={}, fixed={}", comicId, total, fixed);
        } catch (Exception e) {
            log.error("视频元数据修复失败: comicId={}", comicId, e);
            try {
                channel.basicNack(tag, false, false);
} catch (Exception ex) {
                log.warn("消息 nack 失败: tag={}", tag, e);
            }
        }
    }
}
