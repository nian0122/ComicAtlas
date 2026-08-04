package com.comicatlas.worker.event;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
                .filter(p -> p.getContainer() == null || !isAlreadyMp4(p.getContainer()))
                .map(ExportMedia::getId)
                .toList();
        if (videoPages.isEmpty()) {
            publisher.completed(cmd);
            log.info("转码命令完成（漫画，无待转码视频）: comicId={}", comicId);
            return;
        }
        List<Long> failedPages = new ArrayList<>();
        for (Long pageId : videoPages) {
            if (processPage(cmd, pageId) != null) {
                failedPages.add(pageId);
            }
        }
        if (failedPages.isEmpty()) {
            publisher.progress(cmd, 100, "转码完成");
            publisher.completed(cmd);
            log.info("转码命令完成（漫画）: comicId={}, videos={}", comicId, videoPages.size());
        } else {
            publisher.failed(cmd, "转码失败视频页: " + failedPages);
        }
    }

    /** 单页转码，返回是否成功；失败原因由 processPage 记录日志并在此统一发布。 */
    private void transcodePage(ManagementCommandRequestedEvent cmd, Long pageId) {
        String error = processPage(cmd, pageId);
        if (error == null) {
            publisher.progress(cmd, 100, "转码完成");
            publisher.completed(cmd);
        } else {
            publisher.failed(cmd, error);
        }
    }

    /** 转码单个视频页。成功返回 null，失败返回错误消息（不在此发布事件）。 */
    private String processPage(ManagementCommandRequestedEvent cmd, Long pageId) {
        ExportMedia media = mediaMapper.selectById(pageId);
        if (media == null || !"VIDEO".equals(media.getMediaType())) {
            return "媒体不存在或非视频: pageId=" + pageId;
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

            Path tempRoot = config.getTempDir() != null ? Path.of(config.getTempDir())
                    : Path.of(System.getProperty("java.io.tmpdir"));
            Files.createDirectories(tempRoot);
            tempFile = tempRoot.resolve(pageId + ".mp4");

            publisher.progress(cmd, 10, "开始转码");
            ProcessBuilder pb = new ProcessBuilder();
            pb.command(buildFfmpegCommand(config.getFfmpegPath(), hqFile.toString(), tempFile.toString()));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();

            if (!proc.waitFor(10, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                throw new IOException("ffmpeg 超时(10min): pageId=" + pageId);
            }
            if (proc.exitValue() != 0) {
                throw new IOException("ffmpeg exit code " + proc.exitValue() + ": pageId=" + pageId);
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
            return null;
        } catch (Exception e) {
            log.error("转码失败: pageId={}", pageId, e);
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isAlreadyMp4(String container) {
        String c = container.toLowerCase();
        return "mp4".equals(c) || "mov".equals(c) || "m4v".equals(c);
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
