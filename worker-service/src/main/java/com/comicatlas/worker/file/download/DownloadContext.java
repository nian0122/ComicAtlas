package com.comicatlas.worker.file.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadContext {

    /** Archiver 直链下载产物文件名。 */
    private static final String ARCHIVE_FILE_NAME = "archive.zip";
    /** 下载方法标识：Archiver 直链（DownloadResult.method 契约值）。 */
    private static final String METHOD_ARCHIVER = "ARCHIVER";
    /** 下载方法标识：Torrent（DownloadResult.method 契约值）。 */
    private static final String METHOD_TORRENT = "TORRENT";
    /** magnet URI 模板（hash + 名称）。 */
    private static final String MAGNET_URI_TEMPLATE = "magnet:?xt=urn:btih:%s&dn=%s";

    private final HttpDownloader httpDownloader;
    private final TorrentDownloader torrentDownloader;
    private final ArchiveDownloader archiveDownloader;

    /**
     * 下载策略优先级：
     * 1. Archiver 直链下载（走 HTTP 代理，最可靠）
     * 2. Torrent 下载（aria2c，国内不通）
     * <p>
     * 与各 Downloader 接口一致声明 {@code throws Exception}：下载失败即业务失败，
     * 向上传播由 MQ 消费层统一失败处理（不在此吞掉）。
     */
    public DownloadResult download(String sourceRef, Path destDir) throws Exception {
        DownloadResult metaResult = httpDownloader.download(sourceRef, destDir);
        Map<String, Object> metadata = metaResult.metadata();

        // 优先 Archiver
        if (metadata != null && metadata.get("archiverKey") != null) {
            try {
                String archiverKey = (String) metadata.get("archiverKey");
                Long galleryId = Long.valueOf(metadata.get("sourceGalleryId").toString());
                String galleryToken = (String) metadata.get("sourceGalleryToken");

                Path zipFile = destDir.resolve(ARCHIVE_FILE_NAME);
                long bytes = archiveDownloader.download(galleryId, galleryToken, archiverKey, zipFile);
                log.info("Archive downloaded: {} bytes", bytes);
                return new DownloadResult(bytes, METHOD_ARCHIVER, metadata);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;   // 中断不静默回退，向上传播
            } catch (Exception ex) {
                // 回退策略：Archiver 任何非中断失败（IO/超时/解析）都回退 Torrent，
                // 必须捕获全部非中断异常并记录现场，故在此宽泛捕获
                log.warn("Archiver failed, fallback to torrent: {}", ex.getMessage());
            }
        }

        // 兜底 Torrent
        @SuppressWarnings("unchecked")
        // metadata 为 JSON 反序列化结果，torrents 元素类型无法参数化，属预期 unchecked 转换
        List<Map<String, Object>> torrents = (List<Map<String, Object>>) metadata.get("torrents");
        if (torrents != null && !torrents.isEmpty()) {
            Map<String, Object> torrentCandidate = torrents.get(0);
            String magnet = String.format(MAGNET_URI_TEMPLATE,
                    torrentCandidate.get("hash"), torrentCandidate.get("name"));
            log.info("Torrent fallback: {}", torrentCandidate.get("name"));
            DownloadResult torrentResult = torrentDownloader.download(magnet, destDir);
            return new DownloadResult(torrentResult.bytes(), METHOD_TORRENT, metadata);
        }

        throw new RuntimeException("该 Gallery 无 Archiver 也无 Torrent，无法下载");
    }

    public record DownloadResult(long bytes, String method, Map<String, Object> metadata) {
    }
}
