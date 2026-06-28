package com.comicatlas.worker.file.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadContext {
    private final HttpDownloader httpDownloader;
    private final TorrentDownloader torrentDownloader;
    private final ArchiveDownloader archiveDownloader;

    /**
     * 下载策略优先级：
     * 1. Archiver 直链下载（走 HTTP 代理，最可靠）
     * 2. Torrent 下载（aria2c，国内不通）
     */
    public DownloadResult download(String sourceRef, Path destDir) throws Exception {
        DownloadResult metaResult = httpDownloader.download(sourceRef, destDir);
        Map<String, Object> metadata = metaResult.metadata();

        // 优先 Archiver
        if (metadata != null && metadata.get("archiverKey") != null) {
            try {
                String archiverKey = (String) metadata.get("archiverKey");
                Long gid = Long.valueOf(metadata.get("sourceGalleryId").toString());
                String token = (String) metadata.get("sourceGalleryToken");

                Path zipFile = destDir.resolve("archive.zip");
                long bytes = archiveDownloader.download(gid, token, archiverKey, zipFile);
                log.info("Archive downloaded: {} bytes", bytes);
                return new DownloadResult(bytes, "ARCHIVER", metadata);
            } catch (Exception e) {
                log.warn("Archiver failed, fallback to torrent: {}", e.getMessage());
            }
        }

        // 兜底 Torrent
        @SuppressWarnings("unchecked")
        var torrents = (java.util.List<Map<String, Object>>) metadata.get("torrents");
        if (torrents != null && !torrents.isEmpty()) {
            var t = torrents.get(0);
            String magnet = String.format("magnet:?xt=urn:btih:%s&dn=%s",
                t.get("hash"), t.get("name"));
            log.info("Torrent fallback: {}", t.get("name"));
            DownloadResult torrentResult = torrentDownloader.download(magnet, destDir);
            return new DownloadResult(torrentResult.bytes(), "TORRENT", metadata);
        }

        throw new RuntimeException("该 Gallery 无 Archiver 也无 Torrent，无法下载");
    }

    public record DownloadResult(long bytes, String method, Map<String, Object> metadata) {}
}
