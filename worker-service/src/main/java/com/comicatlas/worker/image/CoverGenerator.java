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

    /** 封面在 Go 工具中的固定章节标识 */
    private static final String COVER_CHAPTER_ID = "0";
    /** 封面在 Go 工具中的固定章节名 */
    private static final String COVER_CHAPTER_NO = "cover";
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
                    "-workers", String.valueOf(config.getCover().getWorkers()),
                    "-json"
            ));

            log.info("生成封面: comicId={}, quality={}", comicId, config.getCover().getQuality());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, config.getCover().getTimeoutSeconds(), "封面优化");
            int exitCode = result.exitCode();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "封面优化工具异常退出 exitCode=" + exitCode + ", comicId=" + comicId + ", stdout=" + result.stdout());
            }

            // 输出按源文件名主干命名（Go 工具行为，与 LQ 产物命名一致）。
            // 必须校验产物非空：超大/损坏源图会让工具写出 0 字节文件且退出码仍为 0，
            // 直接落位会产出空封面且不会触发 generateCoverFromNode 的候选兜底
            // （事故场景：漫画 247 的 44.5MB 首图产生 0 字节 cover.webp）。
            Path coverFile = thumbsDir.resolve(COVER_FILE_NAME);
            Path webpFile = thumbsDir.resolve(stemOf(sourceImage.getFileName().toString()) + ".webp");
            if (!Files.exists(webpFile)) {
                throw new RuntimeException("封面优化未生成输出文件: " + webpFile.getFileName());
            }
            if (Files.size(webpFile) == 0) {
                Files.deleteIfExists(webpFile);
                throw new RuntimeException("封面优化输出为空文件（源图过大或解码失败），已清理残留: "
                        + webpFile.getFileName());
            }
            Files.move(webpFile, coverFile, StandardCopyOption.REPLACE_EXISTING);

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
                    "-ss", String.valueOf(config.getCover().getFrameSeekSeconds()),
                    "-i", videoPath.toString(),
                    "-vframes", String.valueOf(config.getCover().getFrameCount()),
                    "-q:v", String.valueOf(config.getCover().getFrameQuality()),
                    frameFile.toString(),
                    "-y"
            );

            log.info("抽取视频封面帧: comicId={}, video={}", comicId, videoPath.getFileName());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            ExternalProcessRunner.ExternalProcessResult result = processRunner.run(
                    processBuilder, config.getCover().getFrameTimeoutSeconds(), "视频封面");
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

    /** 取文件名主干（去最后一个扩展名）：Go 工具输出的 WebP 与输入文件同主干命名。 */
    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
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
