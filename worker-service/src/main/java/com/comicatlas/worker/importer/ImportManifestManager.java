package com.comicatlas.worker.importer;

import com.comicatlas.common.storage.ImportStagingPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 导入清单管理器：位于 mangaRoot/imports/{taskId}/manifest.json。
 * 原子写入（临时文件 + 原子 move），读时校验版本；损坏/版本不符抛 IOException。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportManifestManager {

    /** 清单 schema 版本。 */
    private static final int VERSION = 1;
    /** 导入清单目录名（MANGA_ROOT/imports/{taskId}/）。 */
    private static final String IMPORTS_DIR_NAME = "imports";
    /** 清单文件名。 */
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    /** 原子写清单临时文件名。 */
    private static final String MANIFEST_TMP_FILE_NAME = "manifest.json.tmp";

    private final ObjectMapper objectMapper;

    public Path manifestPath(Path mangaRoot, Long taskId) {
        return mangaRoot.resolve(IMPORTS_DIR_NAME).resolve(String.valueOf(taskId)).resolve(MANIFEST_FILE_NAME);
    }

    public boolean exists(Path mangaRoot, Long taskId) {
        return Files.exists(manifestPath(mangaRoot, taskId));
    }

    public void write(Path mangaRoot, Long taskId, ImportManifest manifest) throws IOException {
        Path target = manifestPath(mangaRoot, taskId);
        Files.createDirectories(target.getParent());
        Path tempPath = target.resolveSibling(MANIFEST_TMP_FILE_NAME);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), manifest);
            Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.deleteIfExists(tempPath);
            throw ex;
        }
        log.info("清单已写入: {}", target);
    }

    public ImportManifest read(Path mangaRoot, Long taskId) throws IOException {
        Path path = manifestPath(mangaRoot, taskId);
        ImportManifest manifest = objectMapper.readValue(path.toFile(), ImportManifest.class);
        if (manifest.version() != VERSION) {
            throw new IOException("清单版本不兼容: " + path + " version=" + manifest.version());
        }
        return manifest;
    }

    public void delete(Path mangaRoot, Long taskId) throws IOException {
        Path dir = manifestPath(mangaRoot, taskId).getParent();
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            log.warn("清理 manifest 文件失败: {}", path, ex);
                        }
                    });
        }
        log.info("恢复点已清理: {}", dir);
    }

    /**
     * 从清单移除指定章节（comicId/globalOrder 前缀）的所有文件条目并重写。
     * 移除后无剩余文件则删除清单目录；删除失败抛 IOException 由调用方延后清理。
     * 该章无条目时幂等返回（不重写）。
     */
    public void rewriteWithoutChapter(Path mangaRoot, Long taskId, Long comicId, Integer globalOrder)
            throws IOException {
        ImportManifest manifest = read(mangaRoot, taskId);
        String prefix = ImportStagingPath.chapterRelativeToHq(comicId, taskId, globalOrder)
                .toString().replace('\\', '/') + "/";
        List<ImportManifest.ImportFile> remaining = new ArrayList<>(manifest.files().size());
        for (ImportManifest.ImportFile file : manifest.files()) {
            if (file.target() == null || !file.target().startsWith(prefix)) {
                remaining.add(file);
            }
        }
        if (remaining.size() == manifest.files().size()) {
            log.info("清单中无该章条目，跳过重写: taskId={}, comicId={}, globalOrder={}",
                    taskId, comicId, globalOrder);
            return;
        }
        if (remaining.isEmpty()) {
            delete(mangaRoot, taskId);
            return;
        }
        ImportManifest filtered = new ImportManifest(
                manifest.version(), manifest.taskId(), manifest.sourceType(),
                manifest.sourceRoot(), manifest.metadata(), remaining);
        write(mangaRoot, taskId, filtered);
    }
}
