package com.comicatlas.worker.image;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 图片优化器：调用外部 Go 工具 image-optimizer.exe 进行并发 WebP 压缩。
 * 零 JVM 内图片处理，全部通过 ProcessBuilder 委托给 Go 子进程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageOptimizer {
    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final ObjectMapper objectMapper;

    private static final long LQ_TIMEOUT_SECONDS = 300;

    /**
     * 对指定章节的 HQ 图片生成 LQ WebP。
     *
     * @param comicId   漫画 ID
     * @param chapterId 章节 ID（仅用于日志和 JSON 回传）
     * @param chapterNo 章节编号（用于拼文件路径）
     * @return Go 工具返回的详细结果
     * @throws RuntimeException Go 工具超时、异常退出或目录不存在
     */
    public RunResult generateLq(Long comicId, Long chapterId, String chapterNo) {
        String hqDir = Path.of(config.getMangaRoot(), pathBuilder.hqDir(comicId, chapterNo)).toString();
        String lqDir = Path.of(config.getMangaRoot(), pathBuilder.lqDir(comicId, chapterNo)).toString();

        if (!Files.exists(Path.of(hqDir))) {
            throw new RuntimeException("HQ 目录不存在: " + hqDir);
        }
        try {
            Files.createDirectories(Path.of(lqDir));
        } catch (Exception e) {
            throw new RuntimeException("创建 LQ 目录失败: " + lqDir, e);
        }

        int workers = config.getLqWorkers() > 0
                ? config.getLqWorkers()
                : Runtime.getRuntime().availableProcessors();

        List<String> cmd = new ArrayList<>(List.of(
                config.getImageOptimizerPath(),
                "-scan-dir", hqDir,
                "-output-dir", lqDir,
                "-comic-id", comicId.toString(),
                "-chapter-id", chapterId.toString(),
                "-chapter-no", chapterNo,
                "-quality", String.valueOf(config.getLqQuality()),
                "-workers", String.valueOf(workers),
                "-json"
        ));

        log.info("启动图片优化: comicId={}, chapterId={}, chapterNo={}, workers={}, quality={}",
                comicId, chapterId, chapterNo, workers, config.getLqQuality());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (Exception e) {
            throw new RuntimeException("启动图片优化工具失败: " + e.getMessage(), e);
        }

        boolean finished;
        try {
            finished = proc.waitFor(LQ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new RuntimeException("等待图片优化被中断: comicId=" + comicId + ", chapterId=" + chapterId);
        }

        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException(
                    "图片优化超时 (" + LQ_TIMEOUT_SECONDS + "s): comicId=" + comicId + ", chapterId=" + chapterId);
        }

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stdout.append(line).append('\n');
            }
        } catch (Exception e) {
            throw new RuntimeException("读取图片优化输出失败: " + e.getMessage(), e);
        }

        int exitCode = proc.exitValue();
        if (exitCode == 2) {
            throw new RuntimeException(
                    "图片优化参数错误或目录不存在: comicId=" + comicId + ", chapterId=" + chapterId
                            + ", stdout=" + stdout);
        }

        RunResult result;
        try {
            result = objectMapper.readValue(stdout.toString(), RunResult.class);
        } catch (Exception e) {
            throw new RuntimeException(
                    "解析图片优化 JSON 失败: comicId=" + comicId + ", stdout=" + stdout, e);
        }

        log.info("图片优化完成: comicId={}, chapterId={}, total={}, processed={}, skipped={}, failed={}, elapsed={}ms",
                comicId, chapterId, result.getTotal(), result.getProcessed(),
                result.getSkipped(), result.getFailed(), result.getElapsedMs());
        return result;
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
