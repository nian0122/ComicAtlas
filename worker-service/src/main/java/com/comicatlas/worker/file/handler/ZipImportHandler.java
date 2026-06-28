package com.comicatlas.worker.file.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZipImportHandler {

    private final com.comicatlas.worker.file.extract.ZipExtractor zipExtractor;
    private final DirectoryImportHandler directoryHandler;

    public Path importZip(String zipPath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path zipFile = Path.of(zipPath);
        if (!Files.exists(zipFile)) throw new RuntimeException("ZIP 文件不存在: " + zipPath);

        Path extractDir = mangaRoot.resolve("temp").resolve(taskId.toString()).resolve("extracted");
        Files.createDirectories(extractDir);
        zipExtractor.extract(zipFile, extractDir);
        log.info("ZIP extracted: {} -> {}", zipPath, extractDir);

        return directoryHandler.importManaged(extractDir.toString(), taskId, comicId, mangaRoot);
    }
}
