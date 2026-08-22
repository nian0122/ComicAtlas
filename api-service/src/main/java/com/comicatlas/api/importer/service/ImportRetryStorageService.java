package com.comicatlas.api.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.storage.ApiStorageProperties;
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
public class ImportRetryStorageService {

    private static final int MANIFEST_VERSION = 1;
    private static final String IMPORTS_DIR_NAME = "imports";
    private static final String MANIFEST_TMP_FILE_NAME = "manifest.json.tmp";

    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();

    private final ApiStorageProperties storageProperties;

    /** 将旧正式章节目录中的文件恢复到当前任务隔离暂存目录。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void restoreFinalizedToStaging(Long taskId, Long comicId, List<Chapter> chapters) {
        Path hqRoot = storageProperties.root(StorageRootKeys.HQ).getPath();
        int restored = 0;
        for (Chapter chapter : chapters) {
            if (chapter.getGlobalOrder() == null) {
                continue;
            }
            Path chapterDir = hqRoot.resolve(String.valueOf(comicId))
                    .resolve(String.valueOf(chapter.getId()));
            Path stagingDir = hqRoot.resolve(ImportStagingPath.chapterRelativeToHq(
                    comicId, taskId, chapter.getGlobalOrder()));
            if (!Files.isDirectory(chapterDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(chapterDir)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    if (restoreFile(file, stagingDir)) {
                        restored++;
                    }
                }
            } catch (IOException ex) {
                log.warn("重试反最终化目录扫描失败（非关键）: dir={}", chapterDir, ex);
            }
        }
        log.info("重试反最终化完成: comicId={}, restoredFiles={}, chapters={}",
                comicId, restored, chapters.size());
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

    private boolean restoreFile(Path chapterFile, Path stagingDir) {
        try {
            Path stagingTarget = stagingDir.resolve(chapterFile.getFileName());
            if (Files.exists(stagingTarget)) {
                long stagingSize = Files.size(stagingTarget);
                long chapterSize = Files.size(chapterFile);
                if (stagingSize != chapterSize) {
                    log.warn("重试反最终化: 暂存与章节目录文件大小不一致，保留暂存版本: file={}", stagingTarget);
                }
                Files.deleteIfExists(chapterFile);
                return false;
            }
            Files.createDirectories(stagingDir);
            Files.move(chapterFile, stagingTarget);
            return true;
        } catch (IOException ex) {
            log.warn("重试反最终化单文件失败（非关键）: file={}", chapterFile, ex);
            return false;
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

    private record ManifestFileEntry(String source, String target, long size) {
    }
}
