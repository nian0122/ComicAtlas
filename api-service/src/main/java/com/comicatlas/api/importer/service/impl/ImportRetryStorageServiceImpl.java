package com.comicatlas.api.importer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.api.storage.config.ApiStorageProperties;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.storage.ImportStagingPath;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.persistence.comic.entity.Chapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 导入重试的文件系统准备操作。
 * <p>文件搬运、扫描和清单重建均在挂起数据库事务后执行，遵守事务内禁止长 IO 的约束。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@lombok.Getter
public class ImportRetryStorageServiceImpl implements com.comicatlas.api.importer.service.ImportRetryStorageService {
    // 导入重试契约由应用服务公开，具体实现保持在导入业务包内。

    private static final int MANIFEST_VERSION = 1;
    private static final String IMPORTS_DIR_NAME = "imports";
    private static final String MANIFEST_TMP_FILE_NAME = "manifest.json.tmp";

    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();

    private final ApiStorageProperties storageProperties;

    /** 将旧正式章节目录整体恢复到当前任务隔离暂存目录。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void restoreFinalizedToStaging(Long taskId, Long comicId, List<Chapter> chapters) {
        Path hqRoot = storageProperties.root(StorageRootKeys.HQ).getPath();
        int restoredChapters = 0;
        for (Chapter chapter : chapters) {
            if (chapter.getGlobalOrder() == null) {
                continue;
            }
            Path chapterDir = hqRoot.resolve(String.valueOf(comicId))
                    .resolve(String.valueOf(chapter.getId()));
            Path stagingDir = hqRoot.resolve(ImportStagingPath.chapterRelativeToHq(
                    comicId, taskId, chapter.getGlobalOrder()));
            if (!Files.exists(chapterDir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                if (Files.isSymbolicLink(chapterDir)
                        || !Files.isDirectory(chapterDir, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("正式章节路径不是安全目录");
                }
                if (Files.exists(stagingDir, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(stagingDir)
                            || !Files.isDirectory(stagingDir, LinkOption.NOFOLLOW_LINKS)
                            || !areChapterDirectoriesIdentical(chapterDir, stagingDir)) {
                        throw new IOException("正式章节目录与暂存目录冲突，保留两边文件");
                    }
                    deleteDirectoryTree(chapterDir);
                } else {
                    Files.createDirectories(stagingDir.getParent());
                    Files.move(chapterDir, stagingDir);
                    restoredChapters++;
                }
            } catch (IOException ex) {
                // 反最终化失败时不能继续删旧章节数据库记录，否则提交后的孤儿清理会丢失原文件。
                throw new IllegalStateException("重试反最终化失败，已停止重试以保留文件: chapterId="
                        + chapter.getId(), ex);
            }
        }
        log.info("重试反最终化完成: comicId={}, restoredChapters={}, chapters={}",
                comicId, restoredChapters, chapters.size());
    }

    /** 根据漫画元数据和当前任务暂存目录重建完整导入清单。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void rebuildManifest(ImportTask task, Long comicId) {
        Path comicMeta = storageProperties.root(StorageRootKeys.METADATA).getPath()
                .resolve(comicId + ".json");
        if (!Files.exists(comicMeta)) {
            log.debug("重试保留原导入清单（persist 未发生，清单完整）: taskId={}", task.getId());
            return;
        }
        try {
            JsonNode metadata = MANIFEST_MAPPER.readTree(comicMeta.toFile());
            Path hqRoot = storageProperties.root(StorageRootKeys.HQ).getPath();
            List<ManifestFileEntry> files = scanStagingFiles(hqRoot, task.getId(), comicId);
            files.sort(Comparator.comparing(ManifestFileEntry::target));

            ObjectNode manifest = MANIFEST_MAPPER.createObjectNode();
            manifest.put("version", MANIFEST_VERSION);
            manifest.put("taskId", task.getId());
            manifest.put("sourceType", task.getSourceType() != null
                    ? task.getSourceType().name() : SourceType.DIRECTORY.name());
            Path stagingRoot = hqRoot.resolve(ImportStagingPath.chapterRelativeToHq(
                    comicId, task.getId(), 1)).getParent();
            manifest.put("sourceRoot", stagingRoot.toString());
            manifest.set("metadata", metadata);
            ArrayNode fileNodes = manifest.putArray("files");
            for (ManifestFileEntry file : files) {
                ObjectNode node = fileNodes.addObject();
                node.put("source", file.source());
                node.put("target", file.target());
                node.put("size", file.size());
            }

            Path target = storageProperties.root(StorageRootKeys.METADATA).getPath().getParent()
                    .resolve(IMPORTS_DIR_NAME).resolve(String.valueOf(task.getId()))
                    .resolve("manifest.json");
            writeManifestAtomically(manifest, target);
            log.info("重试已重建完整导入清单: taskId={}, comicId={}, files={}",
                    task.getId(), comicId, files.size());
        } catch (IOException ex) {
            log.warn("重建导入清单失败（非关键，重试可能沿用残缺清单）: taskId={}, comicId={}",
                    task.getId(), comicId, ex);
        }
    }

    /** 仅在两个副本的相对文件集合和字节内容完全一致时，允许清除重复正式目录。 */
    private boolean areChapterDirectoriesIdentical(Path chapterDirectory, Path stagingDirectory)
            throws IOException {
        List<Path> chapterFiles = listSafeChapterFiles(chapterDirectory);
        List<Path> stagingFiles = listSafeChapterFiles(stagingDirectory);
        if (chapterFiles.size() != stagingFiles.size()) {
            return false;
        }
        for (int fileIndex = 0; fileIndex < chapterFiles.size(); fileIndex++) {
            Path chapterFile = chapterFiles.get(fileIndex);
            Path stagingFile = stagingFiles.get(fileIndex);
            if (!chapterDirectory.relativize(chapterFile).equals(stagingDirectory.relativize(stagingFile))
                    || Files.mismatch(chapterFile, stagingFile) != -1L) {
                return false;
            }
        }
        return true;
    }

    /** 列出平铺章节目录中的普通文件；拒绝链接、子目录和特殊文件。 */
    private List<Path> listSafeChapterFiles(Path chapterDirectory) throws IOException {
        try (Stream<Path> entries = Files.list(chapterDirectory)) {
            List<Path> files = entries.toList();
            if (files.stream().anyMatch(path -> Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("章节目录包含链接、子目录或特殊文件");
            }
            return files.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private void deleteDirectoryTree(Path directory) throws IOException {
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private List<ManifestFileEntry> scanStagingFiles(Path hqRoot, Long taskId, Long comicId)
            throws IOException {
        Path stagingRoot = hqRoot.resolve(ImportStagingPath.comicRelativeToHq(comicId, taskId));
        if (!Files.isDirectory(stagingRoot)) {
            return new ArrayList<>();
        }
        List<ManifestFileEntry> files = new ArrayList<>();
        try (Stream<Path> globalOrderStream = Files.list(stagingRoot)) {
            for (Path globalOrderDir : globalOrderStream.filter(Files::isDirectory).toList()) {
                String globalOrder = globalOrderDir.getFileName().toString();
                try (Stream<Path> fileStream = Files.list(globalOrderDir)) {
                    for (Path file : fileStream.filter(Files::isRegularFile).toList()) {
                        String fileName = file.getFileName().toString();
                        String target = ImportStagingPath.chapterRelativeToHq(
                                comicId, taskId, Integer.valueOf(globalOrder))
                                .resolve(fileName).toString().replace('\\', '/');
                        files.add(new ManifestFileEntry(globalOrder + "/" + fileName,
                                target, Files.size(file)));
                    }
                }
            }
        }
        return files;
    }

    private void writeManifestAtomically(ObjectNode manifest, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tempPath = target.resolveSibling(MANIFEST_TMP_FILE_NAME);
        MANIFEST_MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), manifest);
        Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @lombok.Getter

    private static class ManifestFileEntry {
        private final String source;
        private final String target;
        private final long size;
        public ManifestFileEntry(String source, String target, long size) {
            this.source = source;
            this.target = target;
            this.size = size;
        }
        public String source() { return source; }
        public String target() { return target; }
        public long size() { return size; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ManifestFileEntry)) { return false; }
            ManifestFileEntry that = (ManifestFileEntry) other;
            return java.util.Objects.equals(source, that.source) && java.util.Objects.equals(target, that.target) && java.util.Objects.equals(size, that.size);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(source, target, size); }
        @Override
        public String toString() { return "ManifestFileEntry[" + "source=" + source + ", " + "target=" + target + ", " + "size=" + size + "]"; }
    }
}
