package com.comicatlas.worker.image;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片优化器：调用外部 Go 工具 image-optimizer.exe 进行章节 LQ WebP 并发压缩。
 * 零 JVM 内图片处理，全部通过 ProcessBuilder 委托给 Go 子进程。
 * <p>
 * 职责边界：仅负责章节 LQ 生成；封面生成见 {@link CoverGenerator}（图片/视频封面）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageOptimizer {
    private final WorkerConfig config;
    private final ObjectMapper objectMapper;
    private final ExternalProcessRunner processRunner;

    private static final long LQ_TIMEOUT_SECONDS = 600;

    /**
     * 对指定章节的 HQ 图片生成 LQ WebP（使用显式路径，禁止 globalOrder 拼目录）。
     *
     * @param comicId   漫画 ID
     * @param chapterId 章节 ID（仅用于日志和 JSON 回传）
     * @param hqDir     HQ 源目录绝对路径
     * @param lqDir     LQ 目标目录绝对路径
     * @param force     是否强制重新生成（忽略已存在的 LQ 产物，对应 LQ_REGENERATE）
     * @return Go 工具返回的详细结果
     */
    public RunResult generateLq(Long comicId, Long chapterId, Path hqDir, Path lqDir, boolean force) {
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
        if (force) {
            cmd.add("-force");
        }

        log.info("启动图片优化: comicId={}, chapterId={}, hqDir={}, lqDir={}, workers={}, quality={}, force={}",
                comicId, chapterId, hqDirStr, lqDirStr, workers, config.getLqQuality(), force);
        return runOptimizer(cmd, comicId, chapterId);
    }

    private RunResult runOptimizer(List<String> cmd, Long comicId, Long chapterId) {
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        ExternalProcessRunner.ExternalProcessResult result;
        try {
            result = processRunner.run(processBuilder, LQ_TIMEOUT_SECONDS, "LQ优化");
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
