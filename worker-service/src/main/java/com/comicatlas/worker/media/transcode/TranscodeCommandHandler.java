package com.comicatlas.worker.media.transcode;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.constant.ManagementOperationTypes;
import com.comicatlas.common.constant.MediaTypes;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.common.util.VideoPlayability;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import com.comicatlas.worker.media.transcode.FfmpegTranscoder;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.storage.ManagedStoragePath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** 命令目标类型：漫画级（批量操作 API 创建的 COMIC 目标 item）。 */
    private static final String TARGET_TYPE_COMIC = ManagementOperationTypes.TARGET_COMIC;

    /** 媒体类型：视频。 */
    private static final String MEDIA_TYPE_VIDEO = MediaTypes.VIDEO;

    /** 转码中断的错误标识：区别于普通失败，漫画级循环据此终止。 */
    private static final String ERROR_INTERRUPTED = "TRANSCODE_INTERRUPTED";

    /** 转码产物后缀（H.264 + AAC MP4）。 */
    private static final String MP4_SUFFIX = ".mp4";

    /** 进度值：转码开始。 */
    private static final int PROGRESS_START = 10;

    /** 进度值：转码完成。 */
    private static final int PROGRESS_DONE = 100;

    /** 中断时的失败消息（发布 failed 事件）。 */
    private static final String INTERRUPTED_ERROR_MESSAGE = "转码被中断";

    private final MediaReadMapper mediaMapper;
    private final WorkerConfig config;
    private final ManagementCommandPublisher publisher;
    private final MediaAnalyzer mediaAnalyzer;
    private final FfmpegTranscoder ffmpegTranscoder;

    public void transcode(ManagementCommandRequestedEvent cmd) {
        Long targetId = cmd.targetId();
        if (TARGET_TYPE_COMIC.equals(cmd.targetType())) {
            transcodeComic(cmd, targetId);
        } else {
            transcodePage(cmd, targetId);
        }
    }

    /** 漫画级：展开为所有待转码视频页逐页转码，聚合失败页列表。 */
    private void transcodeComic(ManagementCommandRequestedEvent cmd, Long comicId) {
        // selectByComicId 一次性取回全部页数据，循环复用实体，避免对每页重复查询（N+1）
        List<MediaRecord> pages = mediaMapper.selectByComicId(comicId);
        List<MediaRecord> videoPages = pages.stream()
                .filter(page -> MEDIA_TYPE_VIDEO.equals(page.getMediaType()))
                .filter(page -> VideoPlayability.isTranscodable(page.getWidth(), page.getHeight()))
                .filter(page -> !VideoPlayability.isBrowserPlayable(page.getVideoCodec(), page.getContainer()))
                .toList();
        if (videoPages.isEmpty()) {
            publisher.completed(cmd);
            log.info("转码命令完成（漫画，无待转码视频）: comicId={}", comicId);
            return;
        }
        List<Long> failedPages = new ArrayList<>();
        boolean interrupted = false;
        for (MediaRecord media : videoPages) {
            TranscodeResult result = processPage(cmd, media);
            if (result.error() == null) {
                continue;
            }
            if (ERROR_INTERRUPTED.equals(result.error())) {
                // 中断已置位：终止循环，避免后续页重复启动并立即销毁 ffmpeg 的噪音
                interrupted = true;
                break;
            }
            failedPages.add(media.getId());
        }
        if (interrupted) {
            publisher.failed(cmd, INTERRUPTED_ERROR_MESSAGE);
            log.warn("转码命令被中断: comicId={}", comicId);
            return;
        }
        if (failedPages.isEmpty()) {
            publisher.progress(cmd, PROGRESS_DONE, "转码完成");
            publisher.completed(cmd);
            log.info("转码命令完成（漫画）: comicId={}, videos={}", comicId, videoPages.size());
        } else {
            publisher.failed(cmd, "转码失败视频页: " + failedPages);
        }
    }

    /** 单页转码，按结果发布完成（携带实测元数据）或失败事件。 */
    private void transcodePage(ManagementCommandRequestedEvent cmd, Long pageId) {
        TranscodeResult result = processPageById(cmd, pageId);
        if (result.error() == null) {
            publisher.progress(cmd, PROGRESS_DONE, "转码完成");
            publisher.completed(cmd, result.transcode());
        } else if (ERROR_INTERRUPTED.equals(result.error())) {
            publisher.failed(cmd, INTERRUPTED_ERROR_MESSAGE);
        } else {
            publisher.failed(cmd, result.error());
        }
    }

    /** 按 pageId 加载媒体实体后委托实体重载（MEDIA 级入口）。 */
    private TranscodeResult processPageById(ManagementCommandRequestedEvent cmd, Long pageId) {
        MediaRecord media = mediaMapper.selectById(pageId);
        if (media == null) {
            return new TranscodeResult("媒体不存在或非视频: pageId=" + pageId, null);
        }
        return processPage(cmd, media);
    }

    /**
     * 转码单个视频页（复用已加载实体，漫画级循环内不重复查询）。
     * 成功返回 TranscodeResult(null, 实测元数据)，失败返回 TranscodeResult(错误消息, null)（不在此发布事件）。
     */
    private TranscodeResult processPage(ManagementCommandRequestedEvent cmd, MediaRecord media) {
        Long pageId = media.getId();
        if (!MEDIA_TYPE_VIDEO.equals(media.getMediaType())) {
            return new TranscodeResult("媒体不存在或非视频: pageId=" + pageId, null);
        }
        Path tempFile = null;
        try {
            Path hqFile = resolveHqFile(media);
            if (!Files.exists(hqFile)) {
                throw new IOException("HQ 文件不存在: " + hqFile);
            }
            tempFile = createTempFile(pageId);
            publisher.progress(cmd, PROGRESS_START, "开始转码");
            // 统一 ffmpeg 核心（参数/编码器选择/执行）：委托 FfmpegTranscoder，
            // 硬件加速探测与 CPU 回退收敛单处；超时 10 分钟，中断由 Runner 恢复标志并销毁 ffmpeg 后向上传播
            int exitCode = ffmpegTranscoder.transcode(hqFile, tempFile);
            if (exitCode != 0) {
                throw new IOException("ffmpeg exit code " + exitCode + ": pageId=" + pageId);
            }
            if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
                throw new IOException("转码输出文件为空: " + tempFile);
            }
            Path newHqFile = moveTranscodedFile(hqFile, tempFile, pageId);
            log.info("转码完成: pageId={}, newPath={}", pageId, newHqFile);
            String newHqPath = deriveNewHqPath(media.getHqPath(), newHqFile.getFileName().toString());
            TranscodeMediaInfo info = probeTranscodedMetadata(newHqFile, newHqPath);
            return new TranscodeResult(null, info);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("转码命令被中断: pageId={}", pageId);
            return new TranscodeResult(ERROR_INTERRUPTED, null);
        } catch (Exception e) {
            log.error("转码失败: pageId={}", pageId, e);
            return new TranscodeResult(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null);
        } finally {
            cleanupTempFile(pageId, tempFile);
        }
    }

    /** 解析媒体 HQ 文件绝对路径（hqRoot/hqPath 为空时回退为空段）。 */
    private Path resolveHqFile(MediaRecord media) {
        Path hqBase = Path.of(config.getMangaRoot());
        String hqRoot = media.getHqRoot() == null ? "" : media.getHqRoot();
        String hqPath = media.getHqPath() == null ? "" : media.getHqPath();
        return ManagedStoragePath.resolve(hqBase, hqRoot, hqPath);
    }

    /** 创建转码临时文件（复用 Worker 临时目录，与 MANGA_ROOT 同卷）。 */
    private Path createTempFile(Long pageId) throws IOException {
        Path tempRoot = config.resolveTempDir();
        Files.createDirectories(tempRoot);
        return tempRoot.resolve(pageId + MP4_SUFFIX);
    }

    /**
     * 将转码产物从临时文件移动为 HQ 文件：命中同目录同名 {@code {base}.mp4} 时使用防撞名
     * {@code {base}.transcoded-{pageId}.mp4}，随后删除旧 HQ 文件。返回最终 HQ 文件路径。
     */
    private Path moveTranscodedFile(Path hqFile, Path tempFile, Long pageId) throws IOException {
        Path hqDir = hqFile.getParent();
        String oldName = hqFile.getFileName().toString();
        int lastDotIndex = oldName.lastIndexOf('.');
        String baseName = lastDotIndex > 0 ? oldName.substring(0, lastDotIndex) : oldName;
        Path newHqFile = hqDir.resolve(baseName + MP4_SUFFIX);
        if (!hqFile.equals(newHqFile) && Files.exists(newHqFile)) {
            newHqFile = hqDir.resolve(baseName + ".transcoded-" + pageId + MP4_SUFFIX);
        }
        Files.move(tempFile, newHqFile,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (!hqFile.equals(newHqFile)) {
            Files.deleteIfExists(hqFile);
        }
        return newHqFile;
    }

    /** 清理转码临时文件（失败仅记录警告，不影响转码结果）。 */
    private void cleanupTempFile(Long pageId, Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            log.warn("转码临时文件清理失败: pageId={}, tempFile={}", pageId, tempFile, e);
        }
    }

    /**
     * 用 ffprobe 实测转码后文件元数据；失败时元数据字段降级为 null，
     * 但始终携带 Worker 实际写入的 newHqPath（API 落库依赖它，不能丢失）。
     */
    private TranscodeMediaInfo probeTranscodedMetadata(Path file, String newHqPath) {
        try {
            Optional<ComicMetadata.MediaInfo> opt = mediaAnalyzer.analyzeVideo(file);
            if (opt.isEmpty()) {
                return new TranscodeMediaInfo(null, null, null, null, null, newHqPath);
            }
            ComicMetadata.MediaInfo info = opt.get();
            return new TranscodeMediaInfo(
                    info.duration(), info.container(), info.videoCodec(), info.audioCodec(),
                    info.fileSize(), newHqPath);
        } catch (Exception e) {
            log.warn("转码后元数据探测失败，元数据字段降级为 null: file={}, error={}", file, e.getMessage());
            return new TranscodeMediaInfo(null, null, null, null, null, newHqPath);
        }
    }

    private static String deriveNewHqPath(String hqPath, String newFileName) {
        int idx = hqPath == null ? -1 : hqPath.lastIndexOf('/');
        String dir = idx >= 0 ? hqPath.substring(0, idx + 1) : "";
        return dir + newFileName;
    }

    /** 单页转码结果：error 为 null 表示成功；transcode 为成功时的实测元数据（可能为 null）。 */
    private record TranscodeResult(String error, TranscodeMediaInfo transcode) {
    }
}
