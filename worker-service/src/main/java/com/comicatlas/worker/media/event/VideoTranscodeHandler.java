package com.comicatlas.worker.media.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.VideoTranscodeCompletedEvent;
import com.comicatlas.common.event.VideoTranscodeFailedEvent;
import com.comicatlas.common.event.VideoTranscodeRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.media.transcode.FfmpegTranscoder;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * 视频转码处理器。
 * 消费 VideoTranscodeRequestedEvent，调用 ffmpeg 转码为 H.264/AAC MP4，
 * 原子替换 HQ 文件，完成后发送 VideoTranscodeCompletedEvent 回 API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTranscodeHandler {

    private final RabbitTemplate rabbitTemplate;
    private final WorkerConfig config;
    private final FfmpegTranscoder ffmpegTranscoder;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.VIDEO_TRANSCODE)
    public void handle(VideoTranscodeRequestedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long pageId = event.pageId();
        Long comicId = event.comicId();
        log.info("视频转码开始: pageId={}, comicId={}, container={}", pageId, comicId, event.container());
        mqConsumerSupport.consume(channel, tag, "视频转码: pageId=" + pageId,
                () -> transcodeAndPublish(event),
                e -> publishFailed(event, e),
                MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
    }

    private void transcodeAndPublish(VideoTranscodeRequestedEvent event) throws Exception {
        Long pageId = event.pageId();
        Long comicId = event.comicId();
        Path hqFile = null;
        Path tempFile = null;
        try {
            // 1. 解析 HQ 文件路径
            Path hqDir = Path.of(config.getMangaRoot(), event.hqRoot(), event.hqPath()).getParent();
            hqFile = Path.of(config.getMangaRoot(), event.hqRoot(), event.hqPath());
            if (!Files.exists(hqFile)) {
                throw new IOException("HQ 文件不存在: " + hqFile);
            }

            // 2. 转码到临时目录（统一 ffmpeg 核心：参数/执行收敛单处）
            Path tempRoot = config.resolveTempDir();
            Files.createDirectories(tempRoot);
            tempFile = tempRoot.resolve(pageId + ".mp4");

            int exitCode = ffmpegTranscoder.transcode(hqFile, tempFile);
            if (exitCode != 0) {
                throw new IOException("ffmpeg exit code " + exitCode + ": pageId=" + pageId);
            }

            // 3. 验证临时文件
            if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
                throw new IOException("转码输出文件为空: " + tempFile);
            }

            // 4. 构建新 hqPath — 同目录，.mp4 扩展名
            String oldPath = event.hqPath();
            String newFileName = oldPath.substring(oldPath.lastIndexOf('/') + 1);
            int dotIdx = newFileName.lastIndexOf('.');
            if (dotIdx > 0) {
                newFileName = newFileName.substring(0, dotIdx);
            }
            String baseName = newFileName;
            newFileName = baseName + ".mp4";
            Path newHqFile = hqDir.resolve(newFileName);
            if (!hqFile.equals(newHqFile) && Files.exists(newHqFile)) {
                newFileName = baseName + ".transcoded-" + pageId + ".mp4";
                newHqFile = hqDir.resolve(newFileName);
            }
            String newHqPath = oldPath.substring(0, oldPath.lastIndexOf('/') + 1) + newFileName;

            // 5. 原子替换：临时文件搬入 HQ（Metis G2）
            Files.move(tempFile, newHqFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 删除旧文件（仅当扩展名变化时新旧路径才不同）
            if (!hqFile.equals(newHqFile)) {
                Files.deleteIfExists(hqFile);
            }

            long fileSize = Files.size(newHqFile);
            log.info("视频转码完成: pageId={}, newPath={}, size={}", pageId, newHqPath, fileSize);

            // 6. 发送完成事件
            rabbitTemplate.convertAndSend(MqExchanges.VIDEO, MqRoutingKeys.VIDEO_TRANSCODE_COMPLETED,
                new VideoTranscodeCompletedEvent(UUID.randomUUID(), Instant.now(),
                    pageId, comicId, newHqPath, "mp4", "h264", "aac", fileSize));
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ex) { log.warn("清理转码临时文件失败: {}", tempFile, ex); }
            }
        }
    }

    private void publishFailed(VideoTranscodeRequestedEvent event, Exception failure) {
        rabbitTemplate.convertAndSend(MqExchanges.VIDEO, MqRoutingKeys.VIDEO_TRANSCODE_FAILED,
                new VideoTranscodeFailedEvent(UUID.randomUUID(), Instant.now(),
                        event.pageId(), event.comicId(),
                        failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName()));
    }
}
