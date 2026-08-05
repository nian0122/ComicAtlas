package com.comicatlas.worker.image;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    /** 托管的外部进程 stdout 读取线程池（线程名统一为 process-io- 前缀，替代裸线程） */
    private final ThreadPoolTaskExecutor processIoExecutor;

    public ImageOptimizer(WorkerConfig config,
                          FilePathBuilder pathBuilder,
                          ObjectMapper objectMapper,
                          @Qualifier("processIoExecutor") ThreadPoolTaskExecutor processIoExecutor) {
        this.config = config;
        this.pathBuilder = pathBuilder;
        this.objectMapper = objectMapper;
        this.processIoExecutor = processIoExecutor;
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
        processBuilder.redirectErrorStream(true);
        Process process;
        try {
            process = processBuilder.start();
        } catch (Exception e) {
            throw new RuntimeException("启动图片优化工具失败: " + e.getMessage(), e);
        }

        // 必须在 waitFor() 之前消费 stdout，否则管道满后 Go 进程阻塞写 → 死锁
        // 读取任务提交到托管线程池 processIoExecutor（线程名前缀 process-io-），替代裸线程
        StringBuilder processOutput = new StringBuilder();
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    processOutput.append(line).append('\n');
                }
            } catch (Exception e) {
                log.warn("读取图片优化工具输出失败: {}", e.getMessage());
                processOutput.append("__READ_ERROR__:").append(e.getMessage());
            }
        }, processIoExecutor);

        boolean finished;
        try {
            finished = process.waitFor(LQ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new RuntimeException("等待图片优化被中断: comicId=" + comicId + ", chapterId=" + chapterId);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException(
                    "图片优化超时 (" + LQ_TIMEOUT_SECONDS + "s): comicId=" + comicId + ", chapterId=" + chapterId);
        }

        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待图片优化输出读取超时: {}", e.getMessage());
        }

        int exitCode = process.exitValue();
        if (exitCode == 2) {
            throw new RuntimeException(
                    "图片优化参数错误或目录不存在: comicId=" + comicId + ", chapterId=" + chapterId
                            + ", stdout=" + processOutput);
        }

        RunResult result;
        try {
            result = objectMapper.readValue(processOutput.toString(), RunResult.class);
        } catch (Exception e) {
            throw new RuntimeException(
                    "解析图片优化 JSON 失败: comicId=" + comicId + ", stdout=" + processOutput, e);
        }

        log.info("图片优化完成: comicId={}, chapterId={}, total={}, processed={}, skipped={}, failed={}, elapsed={}ms",
                comicId, chapterId, result.getTotal(), result.getProcessed(),
                result.getSkipped(), result.getFailed(), result.getElapsedMs());
        return result;
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
            processBuilder.redirectErrorStream(true);
            Process process;
            try {
                process = processBuilder.start();
            } catch (Exception e) {
                throw new RuntimeException("启动封面优化工具失败: " + e.getMessage(), e);
            }

            // 必须在 waitFor() 之前消费 stdout，否则管道满后 Go 进程阻塞写 → 死锁
            StringBuilder processOutput = new StringBuilder();
            CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        processOutput.append(line).append('\n');
                    }
                } catch (Exception e) {
                    log.warn("读取封面优化工具输出失败: {}", e.getMessage());
                    processOutput.append("__READ_ERROR__:").append(e.getMessage());
                }
            }, processIoExecutor);

            boolean finished;
            try {
                finished = process.waitFor(LQ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("等待封面优化被中断: comicId=" + comicId);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("封面优化超时 (" + LQ_TIMEOUT_SECONDS + "s): comicId=" + comicId);
            }

            try {
                readFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待封面优化输出读取超时: {}", e.getMessage());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "封面优化工具异常退出 exitCode=" + exitCode + ", comicId=" + comicId + ", stdout=" + processOutput);
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
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // 消费 stdout 防止管道死锁（读取任务提交到托管线程池 processIoExecutor）
            StringBuilder processOutput = new StringBuilder();
            CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        processOutput.append(line).append('\n');
                    }
                } catch (Exception e) { log.warn("ffmpeg stdout 读取异常", e); }
            }, processIoExecutor);

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("ffmpeg 抽帧超时: comicId=" + comicId);
            }
            try {
                readFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待 ffmpeg 输出读取超时: {}", e.getMessage());
            }

            int exitCode = process.exitValue();
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
