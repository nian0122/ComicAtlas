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
     * 不强制覆盖：已存在且不旧于源文件的产物由 Go 工具置 skipped。
     *
     * @param comicId   漫画 ID
     * @param chapterId 章节 ID（仅用于日志和 JSON 回传）
     * @param hqDir     HQ 源目录绝对路径
     * @param lqDir     LQ 目标目录绝对路径
     * @return Go 工具返回的详细结果
     */
    public RunResult generateLq(Long comicId, Long chapterId, Path hqDir, Path lqDir) {
        try {
            return generateLq(comicId, chapterId, hqDir, lqDir, false);
        } catch (InterruptedException e) {
            // 兼容既有调用方（LqGenerateHandler）：包装为 RuntimeException 并保留原始 cause；
            // LqCommandHandler 走 5 参变体以精确区分中断。
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "等待图片优化被中断: comicId=" + comicId + ", chapterId=" + chapterId, e);
        }
    }

    /**
     * 对指定章节的 HQ 图片生成 LQ WebP，显式指定是否强制覆盖（{@code force} 映射 Go 的 {@code -force}）。
     * <p>
     * 中断语义：被中断时抛出 {@link InterruptedException} 且线程中断标志已恢复（由
     * {@link ExternalProcessRunner} 保证），调用方负责处理中断并清理。
     *
     * @param comicId   漫画 ID
     * @param chapterId 章节 ID（仅用于日志和 JSON 回传）
     * @param hqDir     HQ 源目录绝对路径
     * @param lqDir     LQ 目标目录绝对路径
     * @param force     true 时传 {@code -force} 强制重新处理（LQ_REGENERATE），普通 LQ 不覆盖已有有效输出
     * @return Go 工具返回的详细结果
     * @throws InterruptedException 执行被中断（中断标志已恢复）
     */
    public RunResult generateLq(Long comicId, Long chapterId, Path hqDir, Path lqDir, boolean force)
            throws InterruptedException {
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

    private RunResult runOptimizer(List<String> cmd, Long comicId, Long chapterId) throws InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        ExternalProcessRunner.ExternalProcessResult result;
        result = processRunner.run(processBuilder, LQ_TIMEOUT_SECONDS, "LQ优化");
        // InterruptedException 由 ExternalProcessRunner 恢复中断标志并销毁子进程后向上传播，
        // 这里不再吞掉，交由调用方（LqCommandHandler）恢复标志并发布失败。

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
        private String sourceRelPath;
        private String targetRelPath;
        private Long inputSize;
        private Long outputSize;
        private Double ratio;
        private String reason;
    }
}
