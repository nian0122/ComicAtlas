package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.parse.ImportContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.*;
import java.util.Comparator;

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

        try {
            zipExtractor.extract(zipFile, extractDir);
            log.info("ZIP extracted: {} -> {}", zipFile, extractDir);

            String fileName = zipFile.getFileName().toString();
            String titleHint = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            ImportContext extractCtx = new ImportContext(
                "DIRECTORY", extractDir, ctx.generateLq(), ctx.overwrite(), titleHint
            );
            return directoryHandler.handle(extractCtx, taskId, comicId, mangaRoot);
        } finally {
            cleanupTemp(mangaRoot.resolve("temp").resolve(taskId.toString()));
        }
    }

    private void cleanupTemp(Path tempDir) {
        if (!Files.exists(tempDir)) return;
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (Exception e) {
            log.warn("Temp cleanup failed: {}", tempDir, e);
        }
    }
}
