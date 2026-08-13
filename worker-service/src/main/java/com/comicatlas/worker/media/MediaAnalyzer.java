package com.comicatlas.worker.media;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * 媒体文件分析器：
 * - 图片：复用 ImageIO 读取宽高，mediaType=IMAGE。
 * - 视频：调用 ffprobe 读取 duration/width/height/container/videoCodec/audioCodec。
 *   ffprobe 不可用或执行失败时，返回 VIDEO 但视频元数据字段为 null（不阻塞导入）。
 *
 * 不进行转码、不生成 poster、不修复 FastStart。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaAnalyzer {

    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".mkv", ".webm", ".mov", ".avi");

    private static final long FFPROBE_TIMEOUT_SECONDS = 15;

    private final WorkerConfig workerConfig;
    private final ObjectMapper objectMapper;
    private final ExternalProcessRunner processRunner;

    /**
     * 分析媒体文件。返回 MediaInfo（pageNumber 默认为 0，由调用方按章节顺序填充）。
     * 文件不存在时返回 mediaType=IMAGE、fileSize=0、宽高为 null 的空记录。
     */
    public ComicMetadata.MediaInfo analyze(Path file) {
        if (file == null) {
            return new ComicMetadata.MediaInfo(null, 0, "MISSING", "NOT_GENERATED",
                    0L, null, null);
        }
        boolean exists = Files.exists(file);
        long size = 0L;
        if (exists) {
            try { size = Files.size(file); } catch (Exception e) { log.warn("读取文件大小失败: {}", file, e); size = 0L; }
        }
        String name = file.getFileName().toString();
        String ext = extensionOf(name).toLowerCase();
        String container = ext.startsWith(".") ? ext.substring(1) : ext;
        if (VIDEO_EXT.contains(ext)) {
            return analyzeVideo(file, name, container, size);
        }
        ImageDimensions dims = exists ? readImageDims(file) : new ImageDimensions(null, null);
        return new ComicMetadata.MediaInfo(name, 0,
                exists ? "READY" : "MISSING", "NOT_GENERATED",
                size, dims.width(), dims.height());
    }

    /**
     * 分析视频文件，返回元数据。
     * 文件不存在或非视频类型时返回 empty。
     * 容器名统一为无点形式（{@code mp4} 而非 {@code .mp4}），与 {@link #analyze} 一致，
     * 避免转码后 probe 写入带点容器导致下游精确比对失配。
     */
    public Optional<ComicMetadata.MediaInfo> analyzeVideo(Path videoFile) {
        if (videoFile == null || !Files.exists(videoFile)) {
            return Optional.empty();
        }
        String name = videoFile.getFileName().toString();
        String ext = extensionOf(name).toLowerCase();
        if (!VIDEO_EXT.contains(ext)) {
            return Optional.empty();
        }
        long size = 0L;
        try { size = Files.size(videoFile); } catch (Exception e) { log.warn("读取视频文件大小失败: {}", videoFile, e); }
        String container = ext.startsWith(".") ? ext.substring(1) : ext;
        return Optional.of(analyzeVideo(videoFile, name, container, size));
    }

    private ComicMetadata.MediaInfo analyzeVideo(Path file, String name, String container, long size) {
        if (!workerConfig.isFfprobeEnabled()) {
            return videoFallback(name, container, size, "disabled");
        }
        // 空路径直接回退：resolveToolPath("") 会解析成 JVM 工作目录（存在的目录），
        // 若继续会误判 ffprobe 可用并尝试把目录当程序执行。
        String configuredPath = workerConfig.getFfprobePath();
        if (configuredPath == null || configuredPath.isBlank()) {
            log.debug("ffprobe 路径未配置，视频 {} 标记为 VIDEO 元数据为 null", name);
            return videoFallback(name, container, size, "ffprobe-unavailable");
        }
        String ffprobe = workerConfig.resolveToolPath(configuredPath).toString();
        if (!isFfprobeAvailable(ffprobe)) {
            log.debug("ffprobe 不可用 (path='{}'), 视频 {} 标记为 VIDEO 元数据为 null", ffprobe, name);
            return videoFallback(name, container, size, "ffprobe-unavailable");
        }
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffprobe,
                    "-v", "error",
                    "-show_format", "-show_streams",
                    "-of", "json",
                    file.toAbsolutePath().toString());
            // 三参重载分离 stderr：损坏流的 NAL 解码错误经 [ffprobe] tag 打日志，
            // stdout 保持纯 JSON 供解析（与 ImageOptimizer 一致），避免合并流破坏 JSON。
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, FFPROBE_TIMEOUT_SECONDS, "ffprobe");
            if (result.exitCode() != 0) {
                log.warn("ffprobe exit={} for {}", result.exitCode(), file);
                return videoFallback(name, container, size, "exit-" + result.exitCode());
            }
            return parseFfprobeJson(name, container, size, result.stdout());
        } catch (InterruptedException e) {
            // 中断已恢复标志，进程已销毁
            return videoFallback(name, container, size, "interrupted");
        } catch (ExternalProcessRunner.ProcessTimeoutException e) {
            log.warn("ffprobe 读取 {} 超时 ({}s)", file, FFPROBE_TIMEOUT_SECONDS);
            return videoFallback(name, container, size, "timeout");
        } catch (Exception e) {
            log.warn("ffprobe 读取 {} 失败", file, e);
            return videoFallback(name, container, size, "exception");
        }
    }

    private ComicMetadata.MediaInfo videoFallback(String name, String container, long size, String reason) {
        return new ComicMetadata.MediaInfo(name, 0, "READY", "NOT_GENERATED",
                size, null, null, "VIDEO", null, normalizeContainer(container), null, null);
    }

    private ComicMetadata.MediaInfo parseFfprobeJson(String name, String container, long size, String json) {
        BigDecimal duration = null;
        Integer width = null, height = null;
        String videoCodec = null, audioCodec = null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode fmt = root.path("format");
            String durationText = fmt.path("duration").asText(null);
            if (durationText != null && !durationText.isEmpty() && !"N/A".equals(durationText)) {
                try { duration = new BigDecimal(durationText); } catch (Exception e) { log.warn("解析视频时长失败: {}", durationText, e); }
            }
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asText("");
                String codec = stream.path("codec_name").asText(null);
                if ("video".equals(type)) {
                    if (videoCodec == null && codec != null && !"N/A".equals(codec)) {
                        videoCodec = codec;
                    }
                    if (width == null) {
                        int w = stream.path("width").asInt(0);
                        if (w > 0) { width = w; }
                    }
                    if (height == null) {
                        int h = stream.path("height").asInt(0);
                        if (h > 0) { height = h; }
                    }
                } else if ("audio".equals(type)) {
                    if (audioCodec == null && codec != null && !"N/A".equals(codec)) {
                        audioCodec = codec;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 ffprobe JSON 失败: {}", e.toString());
        }
        return new ComicMetadata.MediaInfo(name, 0, "READY", "NOT_GENERATED",
                size, width, height, "VIDEO", duration, normalizeContainer(container), videoCodec, audioCodec);
    }

    /** 容器名统一为无点形式：{@code .mp4} → {@code mp4}；null/空白保持原样。 */
    private static String normalizeContainer(String container) {
        if (container == null) {
            return null;
        }
        String trimmed = container.trim();
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }

    /**
     * ffprobe 可用性判定：
     * - 未配置（null/blank）→ 不可用
     * - 仅给名字（如 "ffprobe"）→ 信任 PATH 查找，不预检
     * - 包含路径分隔符 → 检查文件是否存在
     */
    private boolean isFfprobeAvailable(String ffprobe) {
        if (ffprobe == null || ffprobe.isBlank()) { return false; }
        if (ffprobe.contains("/") || ffprobe.contains("\\")) {
            return Files.exists(Path.of(ffprobe));
        }
        return true;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private record ImageDimensions(Integer width, Integer height) {}

    private ImageDimensions readImageDims(Path path) {
        // 1. 优先尝试 ImageIO
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in != null) {
                var readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(in);
                        return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("ImageIO 读取尺寸失败: {}", path, e);
        }
        // 2. 回退：直接解析文件头
        int[] dims = com.comicatlas.common.util.ImageDimensionsReader.read(path);
        if (dims[0] > 0 && dims[1] > 0) {
            return new ImageDimensions(dims[0], dims[1]);
        }
        return new ImageDimensions(null, null);
    }
}
