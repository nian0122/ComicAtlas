package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.extract.ZipExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZipImportHandler {

    private final ZipExtractor zipExtractor;
    private final DirectoryImportHandler directoryHandler;

    /**
     * 解压 ZIP → 调用 DirectoryImportHandler
     */
    public Path importZip(String zipPath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path zipFile = Path.of(zipPath);
        if (!Files.exists(zipFile)) {
            throw new RuntimeException("ZIP 文件不存在: " + zipPath);
        }

        // 解压到临时目录
        Path extractDir = mangaRoot.resolve("temp").resolve(taskId.toString()).resolve("extracted");
        Files.createDirectories(extractDir);
        zipExtractor.extract(zipFile, extractDir);
        log.info("ZIP extracted: {} -> {}", zipPath, extractDir);

        // 委托给 DirectoryImportHandler
        return directoryHandler.importDirectory(extractDir.toString(), taskId, comicId, mangaRoot);
    }
}
