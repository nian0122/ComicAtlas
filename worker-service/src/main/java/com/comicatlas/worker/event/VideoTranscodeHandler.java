package com.comicatlas.worker.event;

import com.comicatlas.common.event.VideoTranscodeCompletedEvent;
import com.comicatlas.common.event.VideoTranscodeFailedEvent;
import com.comicatlas.common.event.VideoTranscodeRequestedEvent;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
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
import java.util.List;
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
    private final ExternalProcessRunner processRunner;

    private static final List<String> FFMPEG_ARGS = List.of(
        "-c:v", "libx264", "-crf", "23", "-preset", "medium",
        "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-y"
    );

    @RabbitListener(queues = "video.transcode.queue")
    public void handle(VideoTranscodeRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long pageId = event.pageId();
        Long comicId = event.comicId();
        log.info("视频转码开始: pageId={}, comicId={}, container={}", pageId, comicId, event.container());

        Path hqFile = null;
        Path tempFile = null;
        try {
            // 1. 解析 HQ 文件路径
            Path hqDir = Path.of(config.getMangaRoot(), event.hqRoot(), event.hqPath()).getParent();
            hqFile = Path.of(config.getMangaRoot(), event.hqRoot(), event.hqPath());
            if (!Files.exists(hqFile)) {
                throw new IOException("HQ 文件不存在: " + hqFile);
            }

            // 2. 转码到临时目录
            Path tempRoot = config.getTempDir() != null ? Path.of(config.getTempDir())
                    : Path.of(System.getProperty("java.io.tmpdir"));
            Files.createDirectories(tempRoot);
            tempFile = tempRoot.resolve(pageId + ".mp4");

            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(buildFfmpegCommand(
                    config.resolveToolPath(config.getFfmpegPath()).toString(),
                    hqFile.toString(), tempFile.toString()));
            // 统一外部进程执行：超时 10 分钟，中断由 Runner 恢复标志并销毁 ffmpeg 后向上传播
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, 600);
            if (result.exitCode() != 0) {
                throw new IOException("ffmpeg exit code " + result.exitCode() + ": pageId=" + pageId);
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
            rabbitTemplate.convertAndSend("comic.video", "video.transcode.completed",
                new VideoTranscodeCompletedEvent(UUID.randomUUID(), Instant.now(),
                    pageId, comicId, newHqPath, "mp4", "h264", "aac", fileSize));

            channel.basicAck(tag, false);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("视频转码被中断: pageId={}", pageId);
            // 非业务失败：不发送 failed 事件，由监听器容器感知中断状态
        } catch (Exception e) {
            log.error("视频转码失败: pageId={}", pageId, e);
            rabbitTemplate.convertAndSend("comic.video", "video.transcode.failed",
                new VideoTranscodeFailedEvent(UUID.randomUUID(), Instant.now(),
                    pageId, comicId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            try {
                channel.basicReject(tag, false);
            } catch (Exception ex) { log.warn("消息 reject 失败: tag={}", tag, ex); }
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ex) { log.warn("清理转码临时文件失败: {}", tempFile, ex); }
            }
        }
    }

    private List<String> buildFfmpegCommand(String ffmpegPath, String input, String output) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath != null ? ffmpegPath : "ffmpeg");
        cmd.add("-i");
        cmd.add(input);
        cmd.addAll(FFMPEG_ARGS);
        cmd.add(output);
        return cmd;
    }
}
