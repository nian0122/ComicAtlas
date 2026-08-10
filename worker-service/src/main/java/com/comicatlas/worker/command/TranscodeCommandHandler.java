package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 视频转码命令处理器（Todo 7 新契约）。
 * <p>
 * <b>入口语义：</b>只处理 {@code MEDIA} 目标（API 已按媒体逐条展开为 MEDIA item，
 * Todo 4 保证 Worker 不再收到聚合 COMIC 转码 item）；非 MEDIA 目标防御性 failed。
 * <p>
 * <b>确定性命名：</b>临时文件 {@code {temp}/{taskId}-{itemId}-{attempt}-{mediaId}.mp4.tmp}，
 * 最终文件 {@code {hqDir}/{taskId}-{itemId}-{attempt}-{mediaId}.mp4}——不使用字符串替换推导
 * {@code .mp4}，文件名对 task/item/attempt/media 完全确定，天然避免与源文件及并发重试冲突。
 * <p>
 * <b>路径 containment：</b>源（HQ 根内）、TEMP（temp 根内）、目标（HQ 根内）分别
 * normalize 后必须位于对应存储根内，越界拒绝并发布失败事件。
 * <p>
 * <b>已有合法最终产物：</b>确定性最终文件已存在且经 ffprobe 验证兼容时，不重复转码，
 * 重新 probe 并重发相同 completed 结果（幂等重投）。
 * <p>
 * <b>发布顺序与旧源保留：</b>先对 temp 产物 ffprobe 兼容验证 → 原子发布最终文件 →
 * probe 最终产物 → 发布 completed。旧源文件永不删除（DB 由 API 更新指向新路径，
 * 旧文件由后续清理策略处理，本处理器不删除）。completed 发布失败时保留源与确定性产物，
 * 抛 {@link TranscodeResultPublishException} 让命令 requeue 重试，禁止把"文件已成功、
 * 结果没发出"误报为不可重试业务失败。
 * <p>
 * <b>中断：</b>捕获 {@link InterruptedException} 恢复线程中断标志、清理临时文件并发布 failed。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeCommandHandler {

    private final ExportMediaMapper mediaMapper;
    private final WorkerConfig config;
    private final StorageProperties storageProperties;
    private final ManagementCommandPublisher publisher;
    private final ExternalProcessRunner processRunner;
    private final MediaAnalyzer mediaAnalyzer;

    /**
     * 输出兼容矩阵（与 api-service {@code VideoCompatibilityPolicy} 等价规则）。
     * Worker 不依赖 api-service，故此处收敛为同一矩阵的本地副本：mp4/m4v 容器 +
     * h264/avc/avc1 视频编码 + 空音频或 aac。禁止两处规则漂移。
     */
    private static final Set<String> COMPATIBLE_CONTAINERS = Set.of("mp4", "m4v");
    private static final Set<String> COMPATIBLE_VIDEO_CODECS = Set.of("h264", "avc", "avc1");
    private static final Set<String> COMPATIBLE_AUDIO_CODECS = Set.of("aac");

    private static final String HQ_ROOT_KEY = "HQ";

    private static final List<String> FFMPEG_ARGS = List.of(
        "-c:v", "libx264", "-crf", "23", "-preset", "medium",
        "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-y"
    );

    /**
     * 转码结果发布失败标记：产物已成功但 completed 事件未发出。
     * 调用方（ManagementCommandDispatcher）据此 requeue 命令，而非发布 failed 误报业务失败。
     */
    public static class TranscodeResultPublishException extends RuntimeException {
        public TranscodeResultPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 执行转码命令（仅 MEDIA 目标）。
     * <p>
     * 成功 → completed 携带真实产物元数据；业务失败/越界/中断 → failed；
     * 产物已成功但结果发布失败 → 抛 {@link TranscodeResultPublishException}（requeue）。
     */
    public void transcode(ManagementCommandRequestedEvent cmd) {
        if (!"MEDIA".equals(cmd.targetType())) {
            publisher.failed(cmd, "转码命令仅支持 MEDIA 目标（API 已按媒体展开）: targetType=" + cmd.targetType());
            return;
        }
        Long mediaId = cmd.targetId();
        Path source = null;
        Path tempFile = null;
        try {
            source = resolveSource(cmd, mediaId);
            tempFile = resolveTempFile(cmd, mediaId);
            Path finalFile = resolveFinalFile(cmd, mediaId, source);

            // 已有合法最终产物：不重复转码，重 probe 并重发相同结果（幂等重投）
            if (isValidExistingProduct(finalFile)) {
                TranscodeMediaInfo existing = buildInfo(finalFile);
                if (existing != null) {
                    publisher.progress(cmd, 100, "转码完成（复用已有产物）");
                    publishCompleted(cmd, existing);
                    log.info("转码复用已有产物: mediaId={}, newPath={}", mediaId, finalFile);
                    return;
                }
            }

            publisher.progress(cmd, 10, "开始转码");
            runFfmpeg(cmd, mediaId, source, tempFile);

            // 输出经 ffprobe 验证兼容后才允许原子发布（不兼容不发布成功）
            ComicMetadata.MediaInfo tempInfo = probeFile(tempFile);
            if (tempInfo == null || !isCompatibleOutput(tempInfo)) {
                throw new IOException("转码输出经 ffprobe 验证不兼容（不发布成功）: mediaId=" + mediaId
                        + ", container=" + (tempInfo != null ? tempInfo.container() : "null")
                        + ", videoCodec=" + (tempInfo != null ? tempInfo.videoCodec() : "null")
                        + ", audioCodec=" + (tempInfo != null ? tempInfo.audioCodec() : "null"));
            }

            atomicPublish(tempFile, finalFile);

            TranscodeMediaInfo info = buildInfo(finalFile);
            if (info == null) {
                throw new IOException("最终产物 ffprobe 探测失败: " + finalFile);
            }
            publisher.progress(cmd, 100, "转码完成");
            publishCompleted(cmd, info);
            log.info("转码完成: mediaId={}, newPath={}", mediaId, finalFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("转码命令被中断: mediaId={}", mediaId);
            publisher.failed(cmd, "转码被中断");
        } catch (TranscodeResultPublishException e) {
            // 产物已成功但结果事件未发出：保留源与确定性产物，抛给 dispatcher requeue（不发布 failed）
            log.warn("转码结果发布失败，命令将 requeue 重试: mediaId={}", mediaId, e);
            throw e;
        } catch (Exception e) {
            log.error("转码失败: mediaId={}", mediaId, e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            cleanupTemp(tempFile);
        }
    }

    // ======================== 路径解析与 containment ========================

    /** 解析并校验源 HQ 文件：必须位于 HQ 存储根内（resolve 内建 {@code ../} 穿越防御）。 */
    private Path resolveSource(ManagementCommandRequestedEvent cmd, Long mediaId) throws Exception {
        ExportMedia media = mediaMapper.selectById(mediaId);
        if (media == null || !"VIDEO".equals(media.getMediaType())) {
            throw new IllegalStateException("媒体不存在或非视频: mediaId=" + mediaId);
        }
        if (!HQ_ROOT_KEY.equals(media.getHqRoot())) {
            throw new IllegalStateException("转码源必须位于 HQ 存储根内: mediaId=" + mediaId
                    + ", hqRoot=" + media.getHqRoot());
        }
        if (media.getHqPath() == null || media.getHqPath().isBlank()) {
            throw new IllegalStateException("媒体 HQ 路径为空: mediaId=" + mediaId);
        }
        Path source = requireHqRoot().resolve(media.getHqPath());
        if (!Files.exists(source)) {
            throw new IOException("HQ 文件不存在: " + source);
        }
        return source.toAbsolutePath().normalize();
    }

    /** 解析临时文件：{temp}/{taskId}-{itemId}-{attempt}-{mediaId}.mp4.tmp，必须位于 temp 根内。 */
    private Path resolveTempFile(ManagementCommandRequestedEvent cmd, Long mediaId) throws Exception {
        Path tempRoot = config.resolveTempDir().toAbsolutePath().normalize();
        Files.createDirectories(tempRoot);
        Path temp = tempRoot.resolve(deterministicBaseName(cmd, mediaId) + ".tmp").toAbsolutePath().normalize();
        requireInside(tempRoot, temp, "临时文件");
        return temp;
    }

    /** 解析最终文件：{hqDir}/{taskId}-{itemId}-{attempt}-{mediaId}.mp4，必须位于 HQ 根内。 */
    private Path resolveFinalFile(ManagementCommandRequestedEvent cmd, Long mediaId, Path source) throws Exception {
        Path hqRootPath = requireHqRoot().getPath().toAbsolutePath().normalize();
        Path hqDir = source.getParent();
        if (hqDir == null) {
            throw new IllegalStateException("源路径无父目录: " + source);
        }
        Path finalFile = hqDir.resolve(deterministicBaseName(cmd, mediaId)).toAbsolutePath().normalize();
        requireInside(hqRootPath, finalFile, "目标文件");
        return finalFile;
    }

    /** 确定性最终文件名（不含 .tmp 后缀）：taskId-itemId-attempt-mediaId.mp4。 */
    private static String deterministicBaseName(ManagementCommandRequestedEvent cmd, Long mediaId) {
        return cmd.taskId() + "-" + cmd.itemId() + "-" + cmd.attempt() + "-" + mediaId + ".mp4";
    }

    /** 路径 containment 校验：candidate normalize 后必须位于 root 内，越界抛非法状态异常。 */
    private static void requireInside(Path root, Path candidate, String what) {
        Path rootNorm = root.toAbsolutePath().normalize();
        Path candidateNorm = candidate.toAbsolutePath().normalize();
        if (!candidateNorm.startsWith(rootNorm)) {
            throw new IllegalStateException(what + " 越界（不在存储根内）: root=" + rootNorm + ", path=" + candidateNorm);
        }
    }

    private StorageRoot requireHqRoot() {
        StorageRoot root = storageProperties.getRoots().get(HQ_ROOT_KEY);
        if (root == null || root.getPath() == null) {
            throw new IllegalStateException("HQ 存储根未配置");
        }
        return root;
    }

    // ======================== 转码执行 ========================

    /** 判断确定性最终产物是否为可复用的合法产物（存在 + ffprobe 兼容）。 */
    private boolean isValidExistingProduct(Path finalFile) {
        if (!Files.isRegularFile(finalFile)) {
            return false;
        }
        ComicMetadata.MediaInfo info = probeFile(finalFile);
        return info != null && isCompatibleOutput(info);
    }

    /** 执行 ffmpeg 转码到临时文件；非零退出/空输出抛 IOException。 */
    private void runFfmpeg(ManagementCommandRequestedEvent cmd, Long mediaId, Path source, Path tempFile) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(buildFfmpegCommand(
                config.resolveToolPath(config.getFfmpegPath()).toString(),
                source.toString(), tempFile.toString()));
        int timeoutSeconds = Math.max(1, config.getTranscodeTimeoutSeconds());
        ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, timeoutSeconds);
        if (result.exitCode() != 0) {
            throw new IOException("ffmpeg exit code " + result.exitCode() + ": mediaId=" + mediaId);
        }
        if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
            throw new IOException("转码输出文件为空: " + tempFile);
        }
    }

    /** 原子发布最终产物：优先 ATOMIC_MOVE，不支持时回退普通 move（均带 REPLACE_EXISTING）。 */
    private static void atomicPublish(Path tempFile, Path finalFile) throws Exception {
        try {
            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 发布 completed 事件；发布抛异常时包装为 {@link TranscodeResultPublishException} 向上传播
     * （产物已成功，命令 requeue 重试，不误报业务失败）。
     */
    private void publishCompleted(ManagementCommandRequestedEvent cmd, TranscodeMediaInfo info) {
        try {
            publisher.completed(cmd, info);
        } catch (RuntimeException e) {
            throw new TranscodeResultPublishException("转码结果发布失败: taskId=" + cmd.taskId()
                    + ", itemId=" + cmd.itemId() + ", mediaId=" + cmd.targetId(), e);
        }
    }

    private void cleanupTemp(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            log.warn("转码临时文件清理失败: tempFile={}", tempFile, e);
        }
    }

    // ======================== ffprobe 与兼容判定 ========================

    /** ffprobe 探测文件元数据；探测失败返回 null（由调用方决定成败）。 */
    private ComicMetadata.MediaInfo probeFile(Path file) {
        try {
            return mediaAnalyzer.analyzeVideo(file).orElse(null);
        } catch (Exception e) {
            log.warn("ffprobe 探测失败: file={}, error={}", file, e.getMessage());
            return null;
        }
    }

    /** 依据最终产物构建 completed 携带的 TranscodeMediaInfo（真实 hqRoot/hqPath/尺寸/元数据）。 */
    private TranscodeMediaInfo buildInfo(Path file) {
        ComicMetadata.MediaInfo info = probeFile(file);
        if (info == null) {
            return null;
        }
        Path root = requireHqRoot().getPath().toAbsolutePath().normalize();
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return new TranscodeMediaInfo(
                info.duration(), info.container(), info.videoCodec(), info.audioCodec(),
                info.fileSize(), HQ_ROOT_KEY, relative, info.width(), info.height());
    }

    private static boolean isCompatibleOutput(ComicMetadata.MediaInfo info) {
        return isCompatibleOutput(info.container(), info.videoCodec(), info.audioCodec());
    }

    /**
     * 输出兼容判定：mp4/m4v 容器 + h264/avc/avc1 视频编码 + 空音频或 aac。
     * 与 api-service VideoCompatibilityPolicy 矩阵一致，禁止两处规则漂移。
     */
    static boolean isCompatibleOutput(String container, String videoCodec, String audioCodec) {
        String containerNorm = normalize(container);
        String videoCodecNorm = normalize(videoCodec);
        String audioCodecNorm = normalize(audioCodec);
        if (!COMPATIBLE_CONTAINERS.contains(containerNorm)) {
            return false;
        }
        if (!COMPATIBLE_VIDEO_CODECS.contains(videoCodecNorm)) {
            return false;
        }
        return audioCodecNorm.isEmpty() || COMPATIBLE_AUDIO_CODECS.contains(audioCodecNorm);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
