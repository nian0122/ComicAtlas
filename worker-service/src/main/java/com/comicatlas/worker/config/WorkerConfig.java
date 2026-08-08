package com.comicatlas.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "worker")
public class WorkerConfig {
    private String mangaRoot;
    private String tempDir;
    private String metadataDir;
    private Torrent torrent = new Torrent();
    private Proxy proxy = new Proxy();
    private Zip zip = new Zip();
    private Cover cover = new Cover();
    private String aria2cPath = "tools/aria2c/aria2c.exe";
    private String ffprobePath = "tools/ffmpeg/ffprobe.exe";
    private String ffmpegPath = "tools/ffmpeg/ffmpeg.exe";
    private String imageOptimizerPath = "tools/image-optimizer/image-optimizer.exe";
    /** 工具相对路径的解析基准目录；未配置时回退到 JVM 工作目录 */
    private String toolsBaseDir;
    private int lqQuality = 15;
    private int lqWorkers = 4;
    private int hqDeleteTimeoutSeconds = 60;
    private boolean ffprobeEnabled = true;
    private Map<String, String> storageRoots = new LinkedHashMap<>();
    private String hostMangaRoot;
    private String containerMangaRoot = "/storage";
    private Ehentai ehentai = new Ehentai();

    /**
     * 解析外部工具路径：相对路径基于 {@code toolsBaseDir}（未配置则取 JVM 工作目录）解析为绝对路径。
     */
    public Path resolveToolPath(String toolPath) {
        Path path = Path.of(toolPath);
        if (path.isAbsolute()) {
            return path;
        }
        String base = toolsBaseDir != null && !toolsBaseDir.isBlank()
                ? toolsBaseDir : System.getProperty("user.dir");
        return Path.of(base).resolve(path).normalize();
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
        return Path.of(root, "temp");
    }

    @Bean
    public ObjectMapper objectMapper() {
        // QA 修复注记（task-21）：注册 JavaTimeModule。
        // Worker 需读取 API 写入的 TrashManifestDTO / metadata（含 Instant createdAt），
        // 裸 ObjectMapper 无法反序列化 Instant → 回收清单读取失败、TRASH 命令失败。
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build();
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
    }

    @Data
    public static class Zip {
        private int maxEntries = 100_000;
        private int maxDepth = 200;
        private long maxEntrySize = 2L * 1024 * 1024 * 1024;
        private long maxTotalSize = 30L * 1024 * 1024 * 1024;
    }

    @Data
    public static class Ehentai {
        private String apiUrl = "https://api.e-hentai.org/api.php";
        private String siteUrl = "https://e-hentai.org";
        private String galleryUrlPattern = "e-hentai\\.org/g/(\\d+)/([a-f0-9]+)";
        private String userAgent = "Mozilla/5.0";
    }
}
