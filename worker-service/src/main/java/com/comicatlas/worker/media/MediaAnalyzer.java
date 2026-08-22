package com.comicatlas.worker.media;

import com.comicatlas.common.util.ImageDimensionsReader;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import com.comicatlas.worker.image.ImageDecoder;
import com.comicatlas.worker.image.ImageIoDecoder;
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
import java.util.Iterator;
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

    /** 视频文件扩展名集合（小写，含点）。 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mkv", ".webm", ".mov", ".avi");

    /** 媒体类型：视频。 */
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";

    /** 页面 HQ 状态：文件就绪。 */
    private static final String HQ_STATUS_READY = "READY";

    /** 页面 HQ 状态：文件缺失。 */
    private static final String HQ_STATUS_MISSING = "MISSING";

    /** 页面 LQ 状态：未生成（导入阶段从不自动生成 LQ）。 */
    private static final String LQ_STATUS_NOT_GENERATED = "NOT_GENERATED";

    /** ffprobe JSON 中表示字段值不可用的标记。 */
    private static final String UNAVAILABLE_MARKER = "N/A";

    private final WorkerConfig workerConfig;
    private final ObjectMapper objectMapper;
    private final ExternalProcessRunner processRunner;

    /**
     * 分析媒体文件。返回 MediaInfo（pageNumber 默认为 0，由调用方按章节顺序填充）。
     * 文件不存在时返回 mediaType=IMAGE、fileSize=0、宽高为 null 的空记录。
     */
    public ComicMetadata.MediaInfo analyze(Path file) {
        if (file == null) {
            return missingMedia();
        }
        boolean exists = Files.exists(file);
        long size = exists ? readFileSize(file) : 0L;
        String name = file.getFileName().toString();
        String ext = extensionOf(name).toLowerCase();
        String container = normalizeContainer(ext);
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return doAnalyzeVideo(file, name, container, size);
        }
        ImageDecoder.DecodeResult decoded;
        if (!exists) {
            decoded = new ImageDecoder.DecodeResult(null, false, null, null, "文件不存在");
        } else {
            try {
                decoded = new ImageIoDecoder().inspect(file);
            } catch (java.io.IOException e) {
                decoded = new ImageDecoder.DecodeResult(null, false, null, null, e.getMessage());
            }
        }
        String format = decoded.format() == null ? container : decoded.format().toLowerCase();
        boolean needsConversion = !Set.of("jpeg", "jpg", "png", "gif", "webp", "bmp", "tiff").contains(format);
        return new ComicMetadata.MediaInfo(name, 0,
                exists ? HQ_STATUS_READY : HQ_STATUS_MISSING, LQ_STATUS_NOT_GENERATED,
                size, decoded.width(), decoded.height(), "IMAGE", null, null, null, null,
                format, decoded.decodable(), needsConversion,
                decoded.decodable() ? "NOT_STARTED" : "FAILED", decoded.failureReason());
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
        if (!VIDEO_EXTENSIONS.contains(ext)) {
            return Optional.empty();
        }
        long size = readFileSize(videoFile);
        String container = normalizeContainer(ext);
        return Optional.of(doAnalyzeVideo(videoFile, name, container, size));
    }

    /** 视频核心分析：ffprobe 可用性探测 → 执行 → 解析 JSON，失败一律回退为 VIDEO 元数据为 null。 */
    private ComicMetadata.MediaInfo doAnalyzeVideo(Path file, String name, String container, long size) {
        if (!workerConfig.isFfprobeEnabled()) {
            log.debug("ffprobe 已禁用，视频 {} 标记为 VIDEO 元数据为 null", name);
            return videoFallback(name, container, size);
        }
        // 空路径直接回退：resolveToolPath("") 会解析成 JVM 工作目录（存在的目录），
        // 若继续会误判 ffprobe 可用并尝试把目录当程序执行。
        String configuredPath = workerConfig.getFfprobePath();
        if (configuredPath == null || configuredPath.isBlank()) {
            log.debug("ffprobe 路径未配置，视频 {} 标记为 VIDEO 元数据为 null", name);
            return videoFallback(name, container, size);
        }
        String ffprobe = workerConfig.resolveToolPath(configuredPath).toString();
        if (!isFfprobeAvailable(ffprobe)) {
            log.debug("ffprobe 不可用 (path='{}'), 视频 {} 标记为 VIDEO 元数据为 null", ffprobe, name);
            return videoFallback(name, container, size);
        }
        try {
            // -loglevel fatal：损坏流的 NAL 解码错误（error 级）不再刷屏日志；
            // 三参重载分离 stderr：残留的 fatal 级输出经 [ffprobe] tag 打日志，stdout 保持纯 JSON。
            ProcessBuilder processBuilder = new ProcessBuilder(
                    ffprobe,
                    "-loglevel", "fatal",
                    "-show_format", "-show_streams",
                    "-of", "json",
                    file.toAbsolutePath().toString());
            // 三参重载分离 stderr：损坏流的 NAL 解码错误经 [ffprobe] tag 打日志，
            // stdout 保持纯 JSON 供解析（与 ImageOptimizer 一致），避免合并流破坏 JSON。
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(processBuilder, workerConfig.getMedia().getFfprobeTimeoutSeconds(), "ffprobe");
            if (result.exitCode() != 0) {
                log.warn("ffprobe exit={} for {}", result.exitCode(), file);
                return videoFallback(name, container, size);
            }
            return parseFfprobeJson(name, container, size, result.stdout());
        } catch (InterruptedException e) {
            // ExternalProcessRunner 已恢复中断标志并销毁子进程；此处再恢复一次以符合规范
            Thread.currentThread().interrupt();
            log.warn("ffprobe 读取 {} 被中断", file);
            return videoFallback(name, container, size);
        } catch (ExternalProcessRunner.ProcessTimeoutException e) {
            log.warn("ffprobe 读取 {} 超时 ({}s)", file, workerConfig.getMedia().getFfprobeTimeoutSeconds());
            return videoFallback(name, container, size);
        } catch (Exception e) {
            log.warn("ffprobe 读取 {} 失败", file, e);
            return videoFallback(name, container, size);
        }
    }

    /** 文件缺失时的空记录（mediaType=IMAGE、fileSize=0、宽高为 null）。 */
    private static ComicMetadata.MediaInfo missingMedia() {
        return new ComicMetadata.MediaInfo(null, 0, HQ_STATUS_MISSING, LQ_STATUS_NOT_GENERATED,
                0L, null, null);
    }

    /** 读取文件大小（字节）；读取失败时记录警告并返回 0。 */
    private long readFileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            log.warn("读取文件大小失败: {}", file, e);
            return 0L;
        }
    }

    /** 视频元数据不可用时的回退记录：标记为 VIDEO，视频字段为 null。 */
    private ComicMetadata.MediaInfo videoFallback(String name, String container, long size) {
        return new ComicMetadata.MediaInfo(name, 0, HQ_STATUS_READY, LQ_STATUS_NOT_GENERATED,
                size, null, null, MEDIA_TYPE_VIDEO, null, normalizeContainer(container), null, null);
    }

    private ComicMetadata.MediaInfo parseFfprobeJson(String name, String container, long size, String json) {
        BigDecimal duration = null;
        Integer width = null;
        Integer height = null;
        String videoCodec = null;
        String audioCodec = null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode fmt = root.path("format");
            String durationText = fmt.path("duration").asText(null);
            if (durationText != null && !durationText.isEmpty() && !UNAVAILABLE_MARKER.equals(durationText)) {
                try {
                    duration = new BigDecimal(durationText);
                } catch (Exception e) {
                    log.warn("解析视频时长失败: {}", durationText, e);
                }
            }
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asText("");
                String codec = stream.path("codec_name").asText(null);
                if ("video".equals(type)) {
                    if (videoCodec == null && codec != null && !UNAVAILABLE_MARKER.equals(codec)) {
                        videoCodec = codec;
                    }
                    if (width == null) {
                        int w = stream.path("width").asInt(0);
                        if (w > 0) {
                            width = w;
                        }
                    }
                    if (height == null) {
                        int h = stream.path("height").asInt(0);
                        if (h > 0) {
                            height = h;
                        }
                    }
                } else if ("audio".equals(type)) {
                    if (audioCodec == null && codec != null && !UNAVAILABLE_MARKER.equals(codec)) {
                        audioCodec = codec;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 ffprobe JSON 失败: {}", e.getMessage(), e);
        }
        return new ComicMetadata.MediaInfo(name, 0, HQ_STATUS_READY, LQ_STATUS_NOT_GENERATED,
                size, width, height, MEDIA_TYPE_VIDEO, duration, normalizeContainer(container), videoCodec, audioCodec);
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
        if (ffprobe == null || ffprobe.isBlank()) {
            return false;
        }
        if (ffprobe.contains("/") || ffprobe.contains("\\")) {
            return Files.exists(Path.of(ffprobe));
        }
        return true;
    }

    private static String extensionOf(String name) {
        int lastDotIndex = name.lastIndexOf('.');
        return lastDotIndex >= 0 ? name.substring(lastDotIndex) : "";
    }

    private record ImageDimensions(Integer width, Integer height) {
    }

    private ImageDimensions readImageDims(Path path) {
        // 1. 优先尝试 ImageIO
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
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
        int[] dimensions = ImageDimensionsReader.read(path);
        int width = dimensions[0];
        int height = dimensions[1];
        if (width > 0 && height > 0) {
            return new ImageDimensions(width, height);
        }
        return new ImageDimensions(null, null);
    }
}
