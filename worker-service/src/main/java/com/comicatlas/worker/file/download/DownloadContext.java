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

    /**
     * 下载流程：
     * 1. 调 e-hentai API 获取 metadata（含 torrent 信息）
     * 2. 如果有 torrent → aria2c 下载
     * 3. 无 torrent → 暂不支持（Phase 1 仅 Torrent 下载）
     */
    public DownloadResult download(String sourceUrl, Path destDir) throws Exception {
        // 1. API 获取元数据
        DownloadResult metaResult = httpDownloader.download(sourceUrl, destDir);
        Map<String, Object> metadata = metaResult.metadata();

        // 2. 检查 torrent
        @SuppressWarnings("unchecked")
        var torrents = (java.util.List<Map<String, Object>>) metadata.get("torrents");
        if (torrents != null && !torrents.isEmpty()) {
            var t = torrents.get(0);
            String magnet = String.format("magnet:?xt=urn:btih:%s&dn=%s",
                t.get("hash"), t.get("name"));
            log.info("Torrent found: {}, starting aria2c download", t.get("name"));

            DownloadResult torrentResult = torrentDownloader.download(magnet, destDir);
            return new DownloadResult(torrentResult.bytes(), "TORRENT", metadata);
        }

        log.warn("No torrent available - Phase 1 only supports torrent download");
        throw new RuntimeException("该 Gallery 无 torrent，暂不支持逐页下载（Phase 2）");
    }

    public record DownloadResult(long bytes, String method, Map<String, Object> metadata) {}
}
