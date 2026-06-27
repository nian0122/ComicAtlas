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

    public DownloadResult download(String sourceUrl, String magnetUrl, Path destDir) throws Exception {
        // 先尝试 Torrent（如果有磁力链接）
        if (magnetUrl != null && !magnetUrl.isEmpty()) {
            try {
                var torrentResult = torrentDownloader.download(magnetUrl, destDir);
                log.info("Torrent: {} bytes", torrentResult.bytes());
                return new DownloadResult(torrentResult.bytes(), "TORRENT", null);
            } catch (Exception e) {
                log.warn("Torrent failed, fallback to HTTP: {}", e.getMessage());
            }
        }
        // HTTP 下载（内部调 e-hentai API 获取 metadata + 爬取图片）
        return httpDownloader.download(sourceUrl, destDir);
    }

    /**
     * 先调 e-hentai API 获取 metadata（含 torrent 信息），再决定下载策略
     */
    public DownloadResult downloadWithMetadata(String sourceUrl, Path destDir) throws Exception {
        DownloadResult result = httpDownloader.download(sourceUrl, destDir);
        // 如果有 torrent 且想用 torrent 加速，这里可以优化
        return result;
    }

    public record DownloadResult(long bytes, String method, Map<String, Object> metadata) {
        public String magnetUriFromMetadata() {
            if (metadata == null) return null;
            @SuppressWarnings("unchecked")
            var torrents = (java.util.List<Map<String, Object>>) metadata.get("torrents");
            if (torrents != null && !torrents.isEmpty()) {
                var t = torrents.get(0);
                return String.format("magnet:?xt=urn:btih:%s&dn=%s", t.get("hash"), t.get("name"));
            }
            return null;
        }
    }
}
