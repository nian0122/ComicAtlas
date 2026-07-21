package com.comicatlas.worker.file.parse;

import com.comicatlas.worker.config.WorkerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
            try { size = Files.size(file); } catch (Exception e) { size = 0L; }
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

    private ComicMetadata.MediaInfo analyzeVideo(Path file, String name, String ext, long size) {
        String ffprobe = workerConfig.getFfprobePath();
        if (!isFfprobeAvailable(ffprobe)) {
            log.debug("ffprobe 不可用 (path='{}'), 视频 {} 标记为 VIDEO 元数据为 null", ffprobe, name);
            return videoFallback(name, ext, size, "ffprobe-unavailable");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobe,
                    "-v", "error",
                    "-show_format", "-show_streams",
                    "-of", "json",
                    file.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(FFPROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("ffprobe 读取 {} 超时 ({}s)", file, FFPROBE_TIMEOUT_SECONDS);
                return videoFallback(name, ext, size, "timeout");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (proc.exitValue() != 0) {
                log.warn("ffprobe exit={} for {}", proc.exitValue(), file);
                return videoFallback(name, ext, size, "exit-" + proc.exitValue());
            }
            return parseFfprobeJson(name, ext, size, sb.toString());
        } catch (Exception e) {
            log.warn("ffprobe 读取 {} 失败: {}", file, e.toString());
            return videoFallback(name, ext, size, "exception");
        }
    }

    private ComicMetadata.MediaInfo videoFallback(String name, String ext, long size, String reason) {
        return new ComicMetadata.MediaInfo(name, 0, "READY", "NOT_GENERATED",
                size, null, null, "VIDEO", null, ext, null, null);
    }

    private ComicMetadata.MediaInfo parseFfprobeJson(String name, String ext, long size, String json) {
        BigDecimal duration = null;
        Integer width = null, height = null;
        String videoCodec = null, audioCodec = null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode fmt = root.path("format");
            String d = fmt.path("duration").asText(null);
            if (d != null && !d.isEmpty() && !"N/A".equals(d)) {
                try { duration = new BigDecimal(d); } catch (Exception ignored) { /* 非数字忽略 */ }
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
                        if (w > 0) width = w;
                    }
                    if (height == null) {
                        int h = stream.path("height").asInt(0);
                        if (h > 0) height = h;
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
                size, width, height, "VIDEO", duration, ext, videoCodec, audioCodec);
    }

    /**
     * ffprobe 可用性判定：
     * - 未配置（null/blank）→ 不可用
     * - 仅给名字（如 "ffprobe"）→ 信任 PATH 查找，不预检
     * - 包含路径分隔符 → 检查文件是否存在
     */
    private boolean isFfprobeAvailable(String ffprobe) {
        if (ffprobe == null || ffprobe.isBlank()) return false;
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

    private ImageDimensions readImageDims(Path p) {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in == null) return new ImageDimensions(null, null);
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return new ImageDimensions(null, null);
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.debug("无法读取图片尺寸: {}", p, e);
            return new ImageDimensions(null, null);
        }
    }
}
