package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.common.util.VideoPlayability;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频转码命令处理器（新 envelope 路由）。
 * <p>
 * 依据 command.targetId 转码视频：PAGE 级针对单个视频页；COMIC 级（批量操作
 * API 创建的 COMIC 目标 item）展开为漫画下所有待转码视频页逐页转码，
 * 最终按 item 聚合回传一次。
 * <p>
 * QA 修复注记（task-21）：原实现只有 transcode(pageId)，批量操作创建 COMIC
 * 目标 item 时会把 comicId 误当 pageId 处理。本修复补充 COMIC 级展开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeCommandHandler {

    private final ExportMediaMapper mediaMapper;
    private final WorkerConfig config;
    private final ManagementCommandPublisher publisher;
    private final ExternalProcessRunner processRunner;
    private final MediaAnalyzer mediaAnalyzer;

    /** 单页转码结果：error 为 null 表示成功；transcode 为成功时的实测元数据（可能为 null）。 */
    private record TranscodeResult(String error, TranscodeMediaInfo transcode) {}

    private static final List<String> FFMPEG_ARGS = List.of(
        "-c:v", "libx264", "-crf", "23", "-preset", "medium",
        "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-y"
    );

    public void transcode(ManagementCommandRequestedEvent cmd) {
        Long targetId = cmd.targetId();
        if ("COMIC".equals(cmd.targetType())) {
            transcodeComic(cmd, targetId);
        } else {
            transcodePage(cmd, targetId);
        }
    }

    /** 漫画级：展开为所有待转码视频页逐页转码，聚合失败页列表。 */
    private void transcodeComic(ManagementCommandRequestedEvent cmd, Long comicId) {
        List<ExportMedia> pages = mediaMapper.selectByComicId(comicId);
        List<Long> videoPages = pages.stream()
                .filter(p -> "VIDEO".equals(p.getMediaType()))
                .filter(p -> !VideoPlayability.isBrowserPlayable(p.getVideoCodec(), p.getContainer()))
                .map(ExportMedia::getId)
                .toList();
        if (videoPages.isEmpty()) {
            publisher.completed(cmd);
            log.info("转码命令完成（漫画，无待转码视频）: comicId={}", comicId);
            return;
        }
        List<Long> failedPages = new ArrayList<>();
        boolean interrupted = false;
        for (Long pageId : videoPages) {
            TranscodeResult r = processPage(cmd, pageId);
            if (r.error() == null) {
                continue;
            }
            if ("TRANSCODE_INTERRUPTED".equals(r.error())) {
                // 中断已置位：终止循环，避免后续页重复启动并立即销毁 ffmpeg 的噪音
                interrupted = true;
                break;
            }
            failedPages.add(pageId);
        }
        if (interrupted) {
            publisher.failed(cmd, "转码被中断");
            log.warn("转码命令被中断: comicId={}", comicId);
            return;
        }
        if (failedPages.isEmpty()) {
            publisher.progress(cmd, 100, "转码完成");
            publisher.completed(cmd);
            log.info("转码命令完成（漫画）: comicId={}, videos={}", comicId, videoPages.size());
        } else {
            publisher.failed(cmd, "转码失败视频页: " + failedPages);
        }
    }

    /** 单页转码，按结果发布完成（携带实测元数据）或失败事件。 */
    private void transcodePage(ManagementCommandRequestedEvent cmd, Long pageId) {
        TranscodeResult r = processPage(cmd, pageId);
        if (r.error() == null) {
            publisher.progress(cmd, 100, "转码完成");
            publisher.completed(cmd, r.transcode());
        } else if ("TRANSCODE_INTERRUPTED".equals(r.error())) {
            publisher.failed(cmd, "转码被中断");
        } else {
            publisher.failed(cmd, r.error());
        }
    }

    /** 转码单个视频页。成功返回 TranscodeResult(null, 实测元数据)，失败返回 TranscodeResult(错误消息, null)（不在此发布事件）。 */
    private TranscodeResult processPage(ManagementCommandRequestedEvent cmd, Long pageId) {
        ExportMedia media = mediaMapper.selectById(pageId);
        if (media == null || !"VIDEO".equals(media.getMediaType())) {
            return new TranscodeResult("媒体不存在或非视频: pageId=" + pageId, null);
        }
        Path hqFile = null;
        Path tempFile = null;
        try {
            Path hqBase = Path.of(config.getMangaRoot());
            hqFile = hqBase.resolve(media.getHqRoot() == null ? "" : media.getHqRoot())
                    .resolve(media.getHqPath() == null ? "" : media.getHqPath());
            if (!Files.exists(hqFile)) {
                throw new IOException("HQ 文件不存在: " + hqFile);
            }

            Path tempRoot = config.resolveTempDir();
            Files.createDirectories(tempRoot);
            tempFile = tempRoot.resolve(pageId + ".mp4");

            publisher.progress(cmd, 10, "开始转码");
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
            if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
                throw new IOException("转码输出文件为空: " + tempFile);
            }

            Path hqDir = hqFile.getParent();
            String oldName = hqFile.getFileName().toString();
            int dot = oldName.lastIndexOf('.');
            String baseName = dot > 0 ? oldName.substring(0, dot) : oldName;
            Path newHqFile = hqDir.resolve(baseName + ".mp4");
            if (!hqFile.equals(newHqFile) && Files.exists(newHqFile)) {
                newHqFile = hqDir.resolve(baseName + ".transcoded-" + pageId + ".mp4");
            }

            Files.move(tempFile, newHqFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!hqFile.equals(newHqFile)) {
                Files.deleteIfExists(hqFile);
            }

            log.info("转码完成: pageId={}, newPath={}", pageId, newHqFile);
            TranscodeMediaInfo info = probe(newHqFile);
            return new TranscodeResult(null, info);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("转码命令被中断: pageId={}", pageId);
            return new TranscodeResult("TRANSCODE_INTERRUPTED", null);
        } catch (Exception e) {
            log.error("转码失败: pageId={}", pageId, e);
            return new TranscodeResult(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("转码临时文件清理失败: pageId={}, tempFile={}", pageId, tempFile, e);
                }
            }
        }
    }

    /** 用 ffprobe 实测转码后文件元数据；失败降级为 null。 */
    private TranscodeMediaInfo probe(Path file) {
        try {
            var opt = mediaAnalyzer.analyzeVideo(file);
            if (opt.isEmpty()) {
                return null;
            }
            ComicMetadata.MediaInfo info = opt.get();
            return new TranscodeMediaInfo(
                    info.duration(), info.container(), info.videoCodec(), info.audioCodec(),
                    info.fileSize());
        } catch (Exception e) {
            log.warn("转码后元数据探测失败，降级为 null: file={}, error={}", file, e.getMessage());
            return null;
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
