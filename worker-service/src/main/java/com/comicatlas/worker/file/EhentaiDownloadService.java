package com.comicatlas.worker.file;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.DownloadContext;
import com.comicatlas.worker.file.extract.ZipExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * EHENTAI Gallery 下载服务：下载（Archiver 直链优先 → Torrent 兜底）并解压，
 * 返回可直接交给 {@link com.comicatlas.worker.file.handler.DirectoryImportHandler} 的源目录。
 * <p>
 * 文件搬运与 metadata 写入由统一导入链路（DirectoryImportHandler）负责，本服务不落库不搬文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EhentaiDownloadService {
    private final WorkerConfig config;
    private final DownloadContext downloadContext;
    private final ZipExtractor zipExtractor;

    /**
     * 下载 EHENTAI Gallery 到临时目录并解压。
     *
     * @param taskId    导入任务 ID（用于临时目录隔离）
     * @param sourceRef EHENTAI gallery URL
     * @return 解压后的源目录；下载产物非压缩包时返回下载目录本身
     * @throws Exception 下载或解压失败（中断向上传播，不静默回退）
     */
    public Path downloadToSourceDir(Long taskId, String sourceRef) throws Exception {
        Path tempDir = config.resolveTempDir().resolve(String.valueOf(taskId));
        Files.createDirectories(tempDir);

        DownloadContext.DownloadResult result = downloadContext.download(sourceRef, tempDir);
        log.info("EHENTAI 下载完成: taskId={}, method={}, bytes={}", taskId, result.method(), result.bytes());

        try (var stream = Files.newDirectoryStream(tempDir)) {
            for (Path file : stream) {
                if (zipExtractor.supports(file)) {
                    Path extractDir = tempDir.resolve("extracted");
                    List<Path> extracted = zipExtractor.extract(file, extractDir);
                    log.info("EHENTAI 压缩包已解压: taskId={}, entries={}, archive={}",
                            taskId, extracted.size(), file.getFileName());
                    return extractDir;
                }
            }
        }
        return tempDir;
    }
}
