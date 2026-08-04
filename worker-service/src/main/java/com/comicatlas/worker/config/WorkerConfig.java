package com.comicatlas.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    private String aria2cPath = "aria2c";
    private String ffprobePath = "worker-service/ffmpeg/ffprobe.exe";
    private String ffmpegPath = "ffmpeg";
    private String imageOptimizerPath = "tools/image-optimizer/image-optimizer.exe";
    private int lqQuality = 15;
    private int lqWorkers = 0;
    private int hqDeleteTimeoutSeconds = 60;
    private boolean ffprobeEnabled = true;
    private Map<String, String> storageRoots = new LinkedHashMap<>();
    private String hostMangaRoot;
    private String containerMangaRoot = "/storage";

    @Bean
    public ObjectMapper objectMapper() {
        // QA 修复注记（task-21）：注册 JavaTimeModule。
        // Worker 需读取 API 写入的 TrashManifest / metadata（含 Instant createdAt），
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
    }

    @Data
    public static class Proxy {
        private String host;
        private int port = 7890;
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
}
