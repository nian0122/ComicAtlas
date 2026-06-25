package com.comicatlas.worker.file.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadContext {
    private final HttpDownloader httpDownloader;
    private final TorrentDownloader torrentDownloader;

    public DownloadResult download(String sourceUrl, String magnetUrl, Path destDir) throws Exception {
        if (magnetUrl != null && !magnetUrl.isEmpty()) {
            try {
                long bytes = torrentDownloader.download(magnetUrl, destDir);
                log.info("Torrent download success: {} bytes", bytes);
                return new DownloadResult(bytes, "TORRENT");
            } catch (Exception e) {
                log.warn("Torrent failed, fallback to HTTP: {}", e.getMessage());
            }
        }
        long bytes = httpDownloader.download(sourceUrl, destDir);
        return new DownloadResult(bytes, magnetUrl != null ? "TORRENT_FALLBACK_HTTP" : "HTTP");
    }

    public record DownloadResult(long bytes, String method) {}
}
