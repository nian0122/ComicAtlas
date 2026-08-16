package com.comicatlas.worker.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "worker")
public class WorkerConfig {
    private static final String DEFAULT_CONTAINER_MANGA_ROOT = "/storage";
    private static final String TEMP_DIRECTORY_NAME = "temp";
    /** Commons Compress 分卷大小下限：64 KiB（ZIP 规范要求 split segment 不小于 64 KB）。 */
    private static final long ZIP_SPLIT_SIZE_MIN_BYTES = 64L * 1024;
    /** Commons Compress 分卷大小上限：2^32 - 1（无 Zip64 分卷的最大段大小）。 */
    private static final long ZIP_SPLIT_SIZE_MAX_BYTES = 4_294_967_295L;

    private String mangaRoot;
    private String tempDir;
    private String metadataDir;
    private Torrent torrent = new Torrent();
    private Proxy proxy = new Proxy();
    private Zip zip = new Zip();
    private Cover cover = new Cover();
    private Executor executor = new Executor();
    private Transcode transcode = new Transcode();
    private Image image = new Image();
    private Media media = new Media();
    private Download download = new Download();
    private Lifecycle lifecycle = new Lifecycle();
    private String aria2cPath;
    private String ffprobePath;
    private String ffmpegPath;
    private String imageOptimizerPath;
    /** 工具相对路径的解析基准目录；未配置时回退到 JVM 工作目录 */
    private String toolsBaseDir;
    private int lqQuality = 15;
    private int lqWorkers = 4;
    private int hqDeleteTimeoutSeconds = 60;
    private boolean ffprobeEnabled = true;
    private Map<String, String> storageRoots = new LinkedHashMap<>();
    private String hostMangaRoot;
    private String containerMangaRoot = DEFAULT_CONTAINER_MANGA_ROOT;
    private Ehentai ehentai = new Ehentai();

    /**
     * 解析外部工具路径：相对路径基于 {@code toolsBaseDir}（未配置则取 JVM 工作目录）解析为绝对路径。
     */
    public Path resolveToolPath(String toolPath) {
        if (toolPath == null || toolPath.isBlank()) {
            throw new IllegalArgumentException("外部工具路径不能为空");
        }
        Path path = Path.of(toolPath);
        if (path.isAbsolute()) {
            return path;
        }
        String base = toolsBaseDir != null && !toolsBaseDir.isBlank()
                ? toolsBaseDir : System.getProperty("user.dir");
        return Path.of(base).resolve(path).normalize();
    }

    /**
     * 将 API/前端提交的宿主机路径映射为 Worker 容器内路径。
     * <p>
     * 单个导入和目录扫描必须使用同一套映射规则，否则批量导入会在扫描阶段找不到宿主机目录。
     * 仅匹配配置根目录本身或其子路径，避免把 {@code D:/manga-old} 错误映射成同一存储根。
     */
    public String mapHostPathToContainer(String sourcePath) {
        if (sourcePath == null || hostMangaRoot == null || hostMangaRoot.isBlank()) {
            return sourcePath;
        }
        String normalizedHostRoot = normalizePathSeparators(hostMangaRoot);
        String normalizedSourcePath = normalizePathSeparators(sourcePath);
        while (normalizedHostRoot.length() > 1 && normalizedHostRoot.endsWith("/")) {
            normalizedHostRoot = normalizedHostRoot.substring(0, normalizedHostRoot.length() - 1);
        }
        String containerRoot = containerMangaRoot == null || containerMangaRoot.isBlank()
                ? DEFAULT_CONTAINER_MANGA_ROOT : normalizePathSeparators(containerMangaRoot);
        if (normalizedSourcePath.equalsIgnoreCase(normalizedHostRoot)) {
            return containerRoot;
        }
        if (normalizedSourcePath.length() > normalizedHostRoot.length()
                && normalizedSourcePath.regionMatches(true, 0, normalizedHostRoot, 0, normalizedHostRoot.length())
                && normalizedSourcePath.charAt(normalizedHostRoot.length()) == '/') {
            return containerRoot + normalizedSourcePath.substring(normalizedHostRoot.length());
        }
        return sourcePath;
    }

    private static String normalizePathSeparators(String path) {
        return path.replace('\\', '/');
    }

    /**
     * 解析临时目录：优先 {@code worker.temp-dir}；未配置时回退到 {@code {mangaRoot}/temp}。
     * 禁止回退到系统临时目录——转码产物体积大，需与 MANGA_ROOT 同卷且统一清理。
     */
    public Path resolveTempDir() {
        if (tempDir != null && !tempDir.isBlank()) {
            return Path.of(tempDir);
        }
        String root = mangaRoot != null && !mangaRoot.isBlank()
                ? mangaRoot : System.getProperty("user.dir");
        return Path.of(root, TEMP_DIRECTORY_NAME);
    }

    @Data
    public static class Torrent {
        private int peerDetectTimeout = 30;
        private long minSpeedThreshold = 10240;
        private int speedCheckDuration = 300;
        /** 下载总超时（分钟），超过则销毁 aria2c。默认 120 分钟。 */
        private int timeoutMinutes = 120;
    }

    @Data
    public static class Proxy {
        private String host;
        private int port = 7897;
        private int socksPort = 7897;
    }

    @Data
    public static class Cover {
        private int quality = 25;
        private long timeoutSeconds = 600;
        private long frameTimeoutSeconds = 120;
        private int workers = 1;
        private int frameSeekSeconds = 2;
        private int frameCount = 1;
        private int frameQuality = 2;
    }

    @Data
    public static class Executor {
        private int processIoThreads = 4;
        private int processIoQueueCapacity = 64;
        private int shutdownTimeoutSeconds = 30;
    }

    @Data
    public static class Transcode {
        private long timeoutSeconds = 600;
        private long encoderProbeTimeoutSeconds = 15;
    }

    @Data
    public static class Image {
        private long lqTimeoutSeconds = 600;
    }

    @Data
    public static class Media {
        private long ffprobeTimeoutSeconds = 15;
    }

    @Data
    public static class Download {
        private int httpConnectTimeoutSeconds = 30;
        private int archiveConnectTimeoutSeconds = 60;
        private int archiveRequestTimeoutMinutes = 30;
        private int maxConnectionPerServer = 16;
        private int splitCount = 8;
        private int torrentStopTimeoutSeconds = 60;
        private int seedTimeSeconds;
    }

    @Data
    public static class Lifecycle {
        private int cancellationTtlDays = 7;
        private int metadataRefreshAttemptTtlDays = 7;
    }

    @Data
    public static class Zip {
        private int maxEntries = 100_000;
        private int maxDepth = 200;
        /** 分卷导出单卷最大大小（字节），默认 2 GiB，须落在 Commons Compress 分卷支持范围（64 KiB..4 GiB）。 */
        private long splitSize = 2L * 1024 * 1024 * 1024;
        /** 单条目最大大小（字节），默认与 maxTotalSize 一致（30 GiB）。 */
        private long maxEntrySize = 30L * 1024 * 1024 * 1024;
        /** ZIP 解压内容总大小上限（字节），默认 30 GiB。 */
        private long maxTotalSize = 30L * 1024 * 1024 * 1024;
    }

    /**
     * 启动校验分卷（split ZIP）容量配置边界：splitSize 必须落在 Commons Compress
     * 分卷支持范围（64 KiB..4 GiB）；maxEntrySize 必须满足 0 &lt; maxEntrySize &lt;= maxTotalSize。
     * 非法配置抛 {@link IllegalArgumentException}，异常消息携带字段名以便定位。
     */
    @PostConstruct
    void validateZipConfig() {
        validateRuntimeConfig();
        Zip zipConfig = zip;
        if (zipConfig == null) {
            throw new IllegalArgumentException("worker.zip 配置不能为空");
        }
        if (zipConfig.getMaxEntries() <= 0) {
            throw new IllegalArgumentException("worker.zip.maxEntries 必须大于 0，当前值："
                    + zipConfig.getMaxEntries());
        }
        if (zipConfig.getMaxDepth() <= 0) {
            throw new IllegalArgumentException("worker.zip.maxDepth 必须大于 0，当前值："
                    + zipConfig.getMaxDepth());
        }
        if (zipConfig.getMaxTotalSize() <= 0) {
            throw new IllegalArgumentException("worker.zip.maxTotalSize 必须大于 0，当前值："
                    + zipConfig.getMaxTotalSize());
        }
        if (zipConfig.getSplitSize() < ZIP_SPLIT_SIZE_MIN_BYTES || zipConfig.getSplitSize() > ZIP_SPLIT_SIZE_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "worker.zip.splitSize 必须位于 [" + ZIP_SPLIT_SIZE_MIN_BYTES + ", " + ZIP_SPLIT_SIZE_MAX_BYTES
                            + "] 字节范围内（Commons Compress 分卷限制 64 KiB..4 GiB），当前值：" + zipConfig.getSplitSize());
        }
        if (zipConfig.getMaxEntrySize() <= 0 || zipConfig.getMaxEntrySize() > zipConfig.getMaxTotalSize()) {
            throw new IllegalArgumentException(
                    "worker.zip.maxEntrySize 必须满足 0 < maxEntrySize <= maxTotalSize，"
                            + "当前 maxEntrySize=" + zipConfig.getMaxEntrySize()
                            + ", maxTotalSize=" + zipConfig.getMaxTotalSize());
        }
    }

    private void validateRuntimeConfig() {
        if (executor == null || executor.getProcessIoThreads() <= 0
                || executor.getProcessIoQueueCapacity() <= 0
                || executor.getShutdownTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("worker.executor 必须配置为正数");
        }
        if (transcode == null || transcode.getTimeoutSeconds() <= 0
                || transcode.getEncoderProbeTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("worker.transcode 超时时间必须为正数");
        }
        if (image == null || image.getLqTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("worker.image.lqTimeoutSeconds 必须为正数");
        }
        if (media == null || media.getFfprobeTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("worker.media.ffprobeTimeoutSeconds 必须为正数");
        }
        if (download == null || download.getHttpConnectTimeoutSeconds() <= 0
                || download.getArchiveConnectTimeoutSeconds() <= 0
                || download.getArchiveRequestTimeoutMinutes() <= 0
                || download.getMaxConnectionPerServer() <= 0 || download.getSplitCount() <= 0
                || download.getTorrentStopTimeoutSeconds() < 0 || download.getSeedTimeSeconds() < 0) {
            throw new IllegalArgumentException("worker.download 参数范围无效");
        }
        if (lifecycle == null || lifecycle.getCancellationTtlDays() <= 0
                || lifecycle.getMetadataRefreshAttemptTtlDays() <= 0) {
            throw new IllegalArgumentException("worker.lifecycle TTL 必须为正数");
        }
    }

    @Data
    public static class Ehentai {
        private String apiUrl = "https://api.e-hentai.org/api.php";
        private String siteUrl = "https://e-hentai.org";
        private String galleryUrlPattern = "e-hentai\\.org/g/(\\d+)/([a-f0-9]+)";
        private String userAgent = "Mozilla/5.0";
    }
}
