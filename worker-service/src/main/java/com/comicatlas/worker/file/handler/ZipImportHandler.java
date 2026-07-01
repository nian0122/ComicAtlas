package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.parse.ImportContext;
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

    public Path importZip(ImportContext ctx, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path zipFile = ctx.sourcePath();
        if (!Files.exists(zipFile)) throw new RuntimeException("ZIP 文件不存在: " + zipFile);

        Path extractDir = mangaRoot.resolve("temp").resolve(taskId.toString()).resolve("extracted");
        Files.createDirectories(extractDir);
        zipExtractor.extract(zipFile, extractDir);
        log.info("ZIP extracted: {} -> {}", zipFile, extractDir);

        // 解压后的目录作为新的 sourcePath
        ImportContext extractCtx = new ImportContext(
            "ZIP", "MANAGED", extractDir,
            ctx.generateLq(), ctx.overwrite(), null, null
        );
        return directoryHandler.importManaged(extractCtx, taskId, comicId, mangaRoot);
    }
}
