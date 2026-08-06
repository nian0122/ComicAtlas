package com.comicatlas.worker.image;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片优化器：调用外部 Go 工具 image-optimizer.exe 进行并发 WebP 压缩。
 * 零 JVM 内图片处理，全部通过 ProcessBuilder 委托给 Go 子进程。
 */
@Slf4j
@Component
public class ImageOptimizer {
    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final ObjectMapper objectMapper;
    private final ExternalProcessRunner processRunner;

    public ImageOptimizer(WorkerConfig config,
                          FilePathBuilder pathBuilder,
                          ObjectMapper objectMapper,
                          ExternalProcessRunner processRunner) {
        this.config = config;
        this.pathBuilder = pathBuilder;
        this.objectMapper = objectMapper;
        this.processRunner = processRunner;
    }

    private static final long LQ_TIMEOUT_SECONDS = 600;

    /**
     * 对指定章节的 HQ 图片生成 LQ WebP（使用显式路径，禁止 globalOrder 拼目录）。
     *
     * @param comicId   漫画 ID
     * @param chapterId 章节 ID（仅用于日志和 JSON 回传）
     * @param hqDir     HQ 源目录绝对路径
     * @param lqDir     LQ 目标目录绝对路径
     * @return Go 工具返回的详细结果
     */
    public RunResult generateLq(Long comicId, Long chapterId, Path hqDir, Path lqDir) {
        String hqDirStr = hqDir.toString();
        String lqDirStr = lqDir.toString();

        if (!Files.exists(hqDir)) {
            throw new RuntimeException("HQ 目录不存在: " + hqDirStr);
        }
        try {
            Files.createDirectories(lqDir);
        } catch (Exception e) {
            throw new RuntimeException("创建 LQ 目录失败: " + lqDirStr, e);
        }

        int workers = config.getLqWorkers() > 0
                ? config.getLqWorkers()
                : Runtime.getRuntime().availableProcessors();

        Path optimizerPath = config.resolveToolPath(config.getImageOptimizerPath());
        String chapterNo = hqDir.getFileName().toString();
        List<String> cmd = new ArrayList<>(List.of(
                optimizerPath.toString(),
                "-scan-dir", hqDirStr,
                "-output-dir", lqDirStr,
                "-comic-id", comicId.toString(),
                "-chapter-id", chapterId.toString(),
                "-chapter-no", chapterNo,
                "-quality", String.valueOf(config.getLqQuality()),
                "-workers", String.valueOf(workers),
                "-json"
        ));

        log.info("启动图片优化: comicId={}, chapterId={}, hqDir={}, lqDir={}, workers={}, quality={}",
                comicId, chapterId, hqDirStr, lqDirStr, workers, config.getLqQuality());
        return runOptimizer(cmd, comicId, chapterId);
    }

    private RunResult runOptimizer(List<String> cmd, Long comicId, Long chapterId) {
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        ExternalProcessRunner.ExternalProcessResult result;
        try {
            result = processRunner.run(processBuilder, LQ_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            // runner 已恢复中断标志并销毁子进程
            throw new RuntimeException("等待图片优化被中断: comicId=" + comicId + ", chapterId=" + chapterId, e);
        }

        int exitCode = result.exitCode();
        if (exitCode == 2) {
            throw new RuntimeException(
                    "图片优化参数错误或目录不存在: comicId=" + comicId + ", chapterId=" + chapterId
                            + ", stdout=" + result.stdout());
        }

        RunResult parsed;
        try {
            parsed = objectMapper.readValue(result.stdout(), RunResult.class);
        } catch (Exception e) {
            throw new RuntimeException(
                    "解析图片优化 JSON 失败: comicId=" + comicId + ", stdout=" + result.stdout(), e);
        }

        log.info("图片优化完成: comicId={}, chapterId={}, total={}, processed={}, skipped={}, failed={}, elapsed={}ms",
                comicId, chapterId, parsed.getTotal(), parsed.getProcessed(),
                parsed.getSkipped(), parsed.getFailed(), parsed.getElapsedMs());
        return parsed;
    }

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

            List<String> cmd = new ArrayList<>(List.of(
                    optimizerPath.toString(),
                    "-scan-dir", tempDir.toString(),
                    "-output-dir", thumbsDir.toString(),
                    "-comic-id", comicId.toString(),
                    "-chapter-id", "0",
                    "-chapter-no", "cover",
                    "-quality", String.valueOf(config.getCover().getQuality()),
                    "-workers", "1",
                    "-json"
            ));

            log.info("生成封面: comicId={}, quality={}", comicId, config.getCover().getQuality());

            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, LQ_TIMEOUT_SECONDS);
            int exitCode = result.exitCode();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "封面优化工具异常退出 exitCode=" + exitCode + ", comicId=" + comicId + ", stdout=" + result.stdout());
            }

            Path coverFile = thumbsDir.resolve("cover.webp");
            try (var stream = Files.list(thumbsDir)) {
                Path webpFile = stream
                        .filter(f -> f.getFileName().toString().endsWith(".webp")
                                && !f.getFileName().toString().equals("cover.webp"))
                        .findFirst()
                        .orElse(null);
                if (webpFile != null) {
                    Files.move(webpFile, coverFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            log.info("封面优化完成: comicId={}, output={}", comicId, coverFile);
        } catch (Exception e) {
            throw new RuntimeException("封面优化失败: comicId=" + comicId + ", " + e.getMessage(), e);
        } finally {
            try {
                if (Files.exists(tempDir)) {
                    try (var stream = Files.walk(tempDir)) {
                        stream.sorted(java.util.Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(java.io.File::delete);
                    }
                }
            } catch (Exception e) {
                log.warn("清理封面临时目录失败: {}", tempDir, e);
            }
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
            Path frameFile = tempDir.resolve("frame.jpg");

            Path ffmpegPath = config.resolveToolPath(config.getFfmpegPath());
            if (!Files.exists(ffmpegPath)) {
                throw new RuntimeException("ffmpeg 不可用: " + ffmpegPath);
            }

            List<String> cmd = List.of(
                    ffmpegPath.toString(),
                    "-ss", "2",
                    "-i", videoPath.toString(),
                    "-vframes", "1",
                    "-q:v", "2",
                    frameFile.toString(),
                    "-y"
            );

            log.info("抽取视频封面帧: comicId={}, video={}", comicId, videoPath.getFileName());

            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, 120);
            int exitCode = result.exitCode();
            if (exitCode != 0 || !Files.exists(frameFile) || Files.size(frameFile) == 0) {
                throw new RuntimeException("ffmpeg 抽帧失败 exitCode=" + exitCode + ", comicId=" + comicId);
            }

            // 用 generateCover 优化抽出的帧
            generateCover(comicId, frameFile);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("视频封面生成失败: comicId=" + comicId, e);
        } finally {
            try {
                if (Files.exists(tempDir)) {
                    try (var stream = Files.walk(tempDir)) {
                        stream.sorted(java.util.Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(java.io.File::delete);
                    }
                }
            } catch (Exception e) { log.warn("清理临时目录失败: comicId={}", comicId, e); }
        }
    }

    @Data
    public static class RunResult {
        private Long comicId;
        private Long chapterId;
        private String chapterNo;
        private String scanDir;
        private String outputDir;
        private Integer total;
        private Integer processed;
        private Integer skipped;
        private Integer failed;
        private List<PageResult> pages;
        private Long elapsedMs;
        private Boolean success;
    }

    @Data
    public static class PageResult {
        private Long pageNumber;
        private String status;
        private Long inputSize;
        private Long outputSize;
        private Double ratio;
        private String reason;
    }
}
