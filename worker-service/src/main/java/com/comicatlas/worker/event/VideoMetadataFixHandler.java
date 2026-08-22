package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;
import com.comicatlas.common.event.VideoMetadataFixRequestedEvent;
import com.comicatlas.common.event.payload.VideoMetadataFixResult;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.exporter.persistence.ExportMedia;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.exporter.persistence.ExportMediaMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoMetadataFixHandler {

    private final ExportMediaMapper exportMediaMapper;
    private final MediaAnalyzer mediaAnalyzer;
    private final RabbitTemplate rabbitTemplate;
    private final MqConsumerSupport mqConsumerSupport;

    @Value("${worker.manga-root}")
    private String mangaRoot;

    @RabbitListener(queues = MqQueues.VIDEO_METADATA_FIX)
    public void handle(VideoMetadataFixRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();

        mqConsumerSupport.consume(channel, tag, "视频元数据修复: comicId=" + comicId, () -> {
            long start = System.currentTimeMillis();
            log.info("视频元数据修复开始: comicId={}", comicId);

            List<ExportMedia> videos = exportMediaMapper.selectVideosMissingMetadataByComicId(comicId);

            if (videos.isEmpty()) {
                log.info("无需修复的视频元数据: comicId={}", comicId);
                publishCompleted(comicId, List.of());
                return;
            }

            log.info("待修复视频数量: comicId={}, count={}", comicId, videos.size());
            List<VideoMetadataFixResult> results = new ArrayList<>();

            for (ExportMedia video : videos) {
                try {
                    Path videoFile = Path.of(mangaRoot,
                            video.getHqRoot().toLowerCase(),
                            video.getHqPath());

                    if (!Files.exists(videoFile)) {
                        log.warn("视频文件不存在: pageId={}, path={}", video.getId(), videoFile);
                        continue;
                    }

                    Optional<ComicMetadata.MediaInfo> infoOpt = mediaAnalyzer.analyzeVideo(videoFile);
                    if (infoOpt.isEmpty()) {
                        log.warn("无法分析视频: pageId={}, path={}", video.getId(), videoFile);
                        continue;
                    }

                    ComicMetadata.MediaInfo info = infoOpt.get();

                    String container = null;
                    String hqPath = video.getHqPath();
                    if (hqPath != null) {
                        int dot = hqPath.lastIndexOf('.');
                        if (dot >= 0) {
                            container = hqPath.substring(dot + 1);
                        }
                    }

                    results.add(new VideoMetadataFixResult(
                            video.getId(),
                            info.width(),
                            info.height(),
                            info.duration(),
                            container,
                            info.videoCodec(),
                            info.audioCodec()));

                    log.debug("视频分析成功: pageId={}, {}x{}, duration={}s",
                            video.getId(), info.width(), info.height(), info.duration());
                } catch (Exception e) {
                    log.warn("视频分析失败: pageId={}, path={} — {}",
                            video.getId(), video.getHqPath(), e.getMessage());
                }
            }

            publishCompleted(comicId, results);
            log.info("视频元数据修复完成: comicId={}, 成功={}/{}, elapsed={}ms",
                    comicId, results.size(), videos.size(),
                    System.currentTimeMillis() - start);
        });
    }

    private void publishCompleted(Long comicId, List<VideoMetadataFixResult> results) {
        VideoMetadataFixCompletedEvent completedEvent = new VideoMetadataFixCompletedEvent(
                UUID.randomUUID(), Instant.now(), comicId, results);
        rabbitTemplate.convertAndSend(MqExchanges.IMAGE, MqRoutingKeys.VIDEO_METADATA_FIX_COMPLETED,
                completedEvent);
    }
}
