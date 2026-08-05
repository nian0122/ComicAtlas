package com.comicatlas.worker.file.transcode;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 视频标准化器 — 将非标准视频格式转码为浏览器兼容的 .mp4 (H.264 + AAC)。
 * <p>
 * 两阶段处理：先并行转码到临时目录，全部成功后一次性搬入源目录并删除原始文件。
 * 中途失败不影响源目录。
 */
@Slf4j
@Component
public class VideoNormalizer {

    private final WorkerConfig config;
    private final ThreadPoolTaskExecutor executor;
    private final int ffmpegThreads;

    private static final Set<String> NON_STANDARD_EXT = Set.of(
            ".wmv", ".flv", ".ts", ".avi", ".mov", ".mkv",
            ".mts", ".m2ts", ".vob", ".3gp", ".m4v"
    );

    public VideoNormalizer(WorkerConfig config,
                           @Qualifier("videoNormalizeExecutor") ThreadPoolTaskExecutor executor) {
        this.config = config;
        this.executor = executor;
        this.ffmpegThreads = 2; // ffmpeg 转码线程固定 2，并行度由托管线程池控制
    }

    /**
     * 扫描源目录，将非标准视频并行转码到临时目录，全部成功后搬入源目录。
     *
     * @param sourceDir 源目录
     * @return 成功处理的文件数
     */
    public int normalize(Path sourceDir) {
        if (!Files.exists(sourceDir)) return 0;

        List<Path> files = collectFiles(sourceDir);
        if (files.isEmpty()) return 0;

        String cfgTemp = config.getTempDir();
        Path tempRoot = (cfgTemp != null && !cfgTemp.isBlank())
                ? Path.of(cfgTemp)
                : Path.of(System.getProperty("java.io.tmpdir"));
        Path tempDir;
        try {
            Files.createDirectories(tempRoot);
            tempDir = Files.createTempDirectory(tempRoot, "video-normalize-");
        } catch (IOException e) {
            log.error("创建临时目录失败: {}", tempRoot, e);
            return 0;
        }

        log.info("发现 {} 个非标准视频，转码到临时目录 (并行度={}, ffmpeg线程={})",
                files.size(), executor.getCorePoolSize(), ffmpegThreads);

        ConcurrentHashMap<Path, Path> transcoded = new ConcurrentHashMap<>(); // source → temp-mp4
        AtomicInteger failed = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (Path file : files) {
            futures.add(executor.submit(() ->
                    transcodeToTemp(file, sourceDir, tempDir, transcoded, failed)));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.error("转码任务异常: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                failed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("转码被中断，取消剩余任务");
                for (Future<?> remaining : futures) {
                    remaining.cancel(true);
                }
                return 0;
            }
        }

        // 全部成功后，搬入源目录
        if (failed.get() > 0) {
            log.warn("转码失败 {} 个，已跳过。临时目录保留: {}", failed.get(), tempDir);
            return 0;
        }

        int moved = moveToSource(transcoded);
        deleteRecursively(tempDir);

        log.info("视频标准化完成: 成功={}, 失败={}, 总计={}", moved, failed.get(), files.size());
        return moved;
    }

    private List<Path> collectFiles(Path sourceDir) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::needsTranscode)
                    .forEach(files::add);
        } catch (IOException e) {
            log.error("扫描目录失败: {}", sourceDir, e);
        }
        return files;
    }

    private void transcodeToTemp(Path file, Path sourceDir, Path tempDir,
                                  ConcurrentHashMap<Path, Path> transcoded, AtomicInteger failed) {
        try {
            Path relative = sourceDir.relativize(file);
            Path tempMp4 = tempDir.resolve(toMp4Name(relative));
            Files.createDirectories(tempMp4.getParent());

            if (Files.exists(tempMp4) && isNonEmpty(tempMp4)) {
                log.debug("转码目标已存在: {}", tempMp4.getFileName());
                transcoded.put(file, tempMp4);
                return;
            }

            transcode(file, tempMp4);
            transcoded.put(file, tempMp4);
        } catch (Exception e) {
            log.error("视频标准化失败: {} — {}", file.getFileName(), e.getMessage());
            failed.incrementAndGet();
        }
    }

    /** 将 temp 目录中的 .mp4 搬入源目录，删除原始非标准文件 */
    private int moveToSource(ConcurrentHashMap<Path, Path> transcoded) {
        int count = 0;
        for (var entry : transcoded.entrySet()) {
            Path source = entry.getKey();
            Path tempMp4 = entry.getValue();
            Path targetMp4 = toMp4(source);
            try {
                Files.copy(tempMp4, targetMp4, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source);
                count++;
            } catch (IOException e) {
                log.error("搬入源目录失败: {} → {}", tempMp4, targetMp4, e);
            }
        }
        return count;
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("删除临时文件失败: {}", p, e); }
                    });
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }

    private boolean needsTranscode(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return NON_STANDARD_EXT.stream().anyMatch(name::endsWith);
    }

    /** file.wmv → file.mp4（同目录） */
    private Path toMp4(Path original) {
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return original.resolveSibling(base + ".mp4");
    }

    /** Chapter/file.wmv → Chapter/file.mp4（用于 temp 路径） */
    private Path toMp4Name(Path relative) {
        String name = relative.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path parent = relative.getParent();
        return parent != null ? parent.resolve(base + ".mp4") : Path.of(base + ".mp4");
    }

    private boolean isNonEmpty(Path path) {
        try {
            return Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void transcode(Path input, Path output) throws Exception {
        String ffmpeg = config.resolveToolPath(config.getFfmpegPath()).toString();
        log.info("转码: {} → {} (并行度={}, 线程={})",
                input.getFileName(), output.getFileName(), executor.getCorePoolSize(), ffmpegThreads);

        List<String> cmd = List.of(
                ffmpeg,
                "-i", input.toAbsolutePath().toString(),
                "-c:v", "libx264",
                "-crf", "23",
                "-preset", "medium",
                "-threads", String.valueOf(ffmpegThreads),
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                "-y",
                output.toAbsolutePath().toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder processOutput = new StringBuilder();
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (var br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    processOutput.append(line).append('\n');
                }
            } catch (IOException e) {
                log.warn("读取 ffmpeg 输出失败: {}", e.getMessage());
            }
        }, executor);

        int exitCode = process.waitFor();

        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("等待 ffmpeg 输出读取超时: {}", e.getMessage());
        }

        if (exitCode != 0) {
            String tail = processOutput.length() > 500
                    ? processOutput.substring(processOutput.length() - 500)
                    : processOutput.toString();
            throw new RuntimeException("ffmpeg exit " + exitCode + ": " + tail.trim());
        }

        if (!isNonEmpty(output)) {
            throw new RuntimeException("转码输出文件为空: " + output.getFileName());
        }

        log.debug("转码完成: {}", output.getFileName());
    }
}
