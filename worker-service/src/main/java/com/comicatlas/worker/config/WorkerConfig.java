package com.comicatlas.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "worker")
public class WorkerConfig {
    private String mangaRoot;
    private String tempDir;
    private String metadataDir;
    private Torrent torrent = new Torrent();

    @Data
    public static class Torrent {
        private int peerDetectTimeout = 30;
        private long minSpeedThreshold = 10240;
        private int speedCheckDuration = 300;
    }
}
