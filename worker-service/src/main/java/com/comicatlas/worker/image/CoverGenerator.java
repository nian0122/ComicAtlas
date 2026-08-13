package com.comicatlas.worker.image;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 封面生成器：负责漫画封面 WebP 的生成。
 * <p>
 * 封面来源分两类：
 * <ul>
 *   <li>图片封面（{@link #generateCover}）：直接调用 Go 工具 image-optimizer 优化单张源图片；</li>
 *   <li>视频封面（{@link #generateCoverFromVideo}）：先用 ffmpeg 抽取视频首帧，再复用图片封面流程。</li>
 * </ul>
 * 输出统一落到 {@code {MANGA_ROOT}/thumbs/{comicId}/cover.webp}。
 * <p>
 * 与 {@link ImageOptimizer}（LQ 图片压缩）职责分离：本类只负责封面，不参与章节 LQ 生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverGenerator {

    /** 图片优化子进程超时（秒），封面单图处理通常远快于整章 LQ */
    private static final long COVER_TIMEOUT_SECONDS = 600;
    /** ffmpeg 抽帧子进程超时（秒） */
    private static final long FRAME_TIMEOUT_SECONDS = 120;
    /** 封面在 Go 工具中的固定章节标识 */
    private static final String COVER_CHAPTER_ID = "0";
    /** 封面在 Go 工具中的固定章节名 */
    private static final String COVER_CHAPTER_NO = "cover";
    /** 封面优化并发数（封面单图，固定单 worker） */
    private static final String COVER_WORKERS = "1";
    /** ffmpeg 抽帧起始时间偏移（秒），跳过片头黑场 */
    private static final String FRAME_SEEK_SECONDS = "2";
    /** ffmpeg 抽帧数量 */
    private static final String FRAME_COUNT = "1";
    /** ffmpeg 抽帧质量（2=高质量） */
    private static final String FRAME_QUALITY = "2";
    /** 抽出的临时帧文件名 */
    private static final String FRAME_FILE_NAME = "frame.jpg";
    /** 最终封面文件名 */
    private static final String COVER_FILE_NAME = "cover.webp";

    private final WorkerConfig config;
    private final ExternalProcessRunner processRunner;

    /**
     * 为漫画生成优化封面 WebP，调用 Go 工具处理单张源图片，
     * 输出到 thumbs/{comicId}/cover.webp。
     *
     * @param comicId     漫画 ID
     * @param sourceImage 源封面图片路径（HQ 中已存在的文件）
     * @throws RuntimeException Go 工具超时、异常退出或 IO 错误
     */
    public void generateCover(Long comicId, Path sourceImage) {
        Path tempDir = Path.of(config.getMangaRoot(), "temp", "cover-" + comicId);
        Path thumbsDir = Path.of(config.getMangaRoot(), "thumbs", String.valueOf(comicId));

        try {
            Files.createDirectories(tempDir);
            Files.copy(sourceImage, tempDir.resolve(sourceImage.getFileName()), StandardCopyOption.REPLACE_EXISTING);

            Files.createDirectories(thumbsDir);

            Path optimizerPath = config.resolveToolPath(config.getImageOptimizerPath());

            List<String> command = new ArrayList<>(List.of(
                    optimizerPath.toString(),
                    "-scan-dir", tempDir.toString(),
                    "-output-dir", thumbsDir.toString(),
                    "-comic-id", comicId.toString(),
                    "-chapter-id", COVER_CHAPTER_ID,
                    "-chapter-no", COVER_CHAPTER_NO,
                    "-quality", String.valueOf(config.getCover().getQuality()),
                    "-workers", COVER_WORKERS,
                    "-json"
            ));

            log.info("生成封面: comicId={}, quality={}", comicId, config.getCover().getQuality());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, COVER_TIMEOUT_SECONDS, "封面优化");
            int exitCode = result.exitCode();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "封面优化工具异常退出 exitCode=" + exitCode + ", comicId=" + comicId + ", stdout=" + result.stdout());
            }

            Path coverFile = thumbsDir.resolve(COVER_FILE_NAME);
            try (Stream<Path> stream = Files.list(thumbsDir)) {
                Path webpFile = stream
                        .filter(f -> f.getFileName().toString().endsWith(".webp")
                                && !f.getFileName().toString().equals(COVER_FILE_NAME))
                        .findFirst()
                        .orElse(null);
                if (webpFile != null) {
                    Files.move(webpFile, coverFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            log.info("封面优化完成: comicId={}, output={}", comicId, coverFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("封面优化被中断: comicId=" + comicId, e);
        } catch (Exception e) {
            throw new RuntimeException("封面优化失败: comicId=" + comicId + ", " + e.getMessage(), e);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    /**
     * 从视频文件提取首帧作为封面（全视频漫画回退方案）。
     *
     * @param comicId   漫画 ID
     * @param videoPath 视频文件路径（HQ 中已存在的文件）
     * @throws RuntimeException ffmpeg 不可用、超时或异常退出
     */
    public void generateCoverFromVideo(Long comicId, Path videoPath) {
        Path tempDir = Path.of(config.getMangaRoot(), "temp", "cover-video-" + comicId);
        try {
            Files.createDirectories(tempDir);
            Path frameFile = tempDir.resolve(FRAME_FILE_NAME);

            Path ffmpegPath = config.resolveToolPath(config.getFfmpegPath());
            if (!Files.exists(ffmpegPath)) {
                throw new RuntimeException("ffmpeg 不可用: " + ffmpegPath);
            }

            List<String> command = List.of(
                    ffmpegPath.toString(),
                    "-ss", FRAME_SEEK_SECONDS,
                    "-i", videoPath.toString(),
                    "-vframes", FRAME_COUNT,
                    "-q:v", FRAME_QUALITY,
                    frameFile.toString(),
                    "-y"
            );

            log.info("抽取视频封面帧: comicId={}, video={}", comicId, videoPath.getFileName());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, FRAME_TIMEOUT_SECONDS, "视频封面");
            int exitCode = result.exitCode();
            if (exitCode != 0 || !Files.exists(frameFile) || Files.size(frameFile) == 0) {
                throw new RuntimeException("ffmpeg 抽帧失败 exitCode=" + exitCode + ", comicId=" + comicId);
            }

            // 复用图片封面流程优化抽出的帧
            generateCover(comicId, frameFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("视频封面生成被中断: comicId=" + comicId, e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("视频封面生成失败: comicId=" + comicId, e);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    /** 递归删除临时目录，失败仅告警不影响主流程 */
    private void cleanupTempDir(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                try (Stream<Path> stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
        } catch (Exception e) {
            log.warn("清理临时目录失败: {}", tempDir, e);
        }
    }
}
