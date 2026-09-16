package com.comicatlas.worker.importer.metadata;

import com.comicatlas.common.metadata.file.MetadataFileWriter;
import com.comicatlas.worker.media.ComicMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构建并原子写出导入 metadata，隔离导入流程与元数据文件格式。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportMetadataArtifactService {
    private static final int METADATA_VERSION = 3;
    private static final String METADATA_DIR_NAME = "metadata";
    private static final String JSON_FILE_SUFFIX = ".json";

    private final ObjectMapper objectMapper;

    public JsonNode buildNode(ComicMetadata metadata, Map<String, String> generatedNames) {
        Map<String, Object> comic = new LinkedHashMap<>();
        comic.put("title", metadata.title());
        comic.put("author", metadata.author() == null ? "" : metadata.author());
        comic.put("description", metadata.description() == null ? "" : metadata.description());
        comic.put("tags", metadata.tags());
        List<Map<String, Object>> catalogs = metadata.catalogs().stream().map(catalog -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", catalog.title()); value.put("sortOrder", catalog.sortOrder());
            value.put("parentIndex", catalog.parentIndex()); return value;
        }).toList();
        List<Map<String, Object>> chapters = metadata.chapters().stream().map(chapter -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", chapter.title()); value.put("chapterNo", chapter.chapterNo());
            value.put("sortOrder", chapter.sortOrder()); value.put("globalOrder", chapter.globalOrder());
            value.put("catalogIndex", chapter.catalogIndex()); value.put("sourceDir", chapter.sourceDir());
            value.put("mediaItems", chapter.pages().stream().map(page -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", page.fileName()); item.put("pageNumber", page.pageNumber());
                item.put("hqStatus", page.hqStatus()); item.put("lqStatus", page.lqStatus());
                item.put("fileSize", page.fileSize());
                String relativePath = chapter.sourceDir() != null && !chapter.sourceDir().isBlank()
                        ? chapter.sourceDir() + "/" + page.fileName() : page.fileName();
                String generatedPath = generatedNames.get(relativePath);
                 if (generatedPath != null) {
                     item.put("hqPath", generatedPath);
                 }
                 if (page.width() != null) {
                     item.put("width", page.width());
                 }
                 if (page.height() != null) {
                     item.put("height", page.height());
                 }
                 item.put("mediaType", page.mediaType());
                 if (page.duration() != null) {
                     item.put("duration", page.duration());
                 }
                 if (page.container() != null) {
                     item.put("container", page.container());
                 }
                 if (page.videoCodec() != null) {
                     item.put("videoCodec", page.videoCodec());
                 }
                 if (page.audioCodec() != null) {
                     item.put("audioCodec", page.audioCodec());
                 }
                return item;
            }).toList());
            return value;
        }).toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", METADATA_VERSION); root.put("comic", comic);
        root.put("catalogs", catalogs); root.put("chapters", chapters);
        return objectMapper.valueToTree(root);
    }

    public Path write(JsonNode metadata, Long taskId, Long comicId, Path mangaRoot) throws IOException {
        Path metadataDirectory = mangaRoot.resolve(METADATA_DIR_NAME);
        Files.createDirectories(metadataDirectory);
        String metadataJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Path metadataPath = metadataDirectory.resolve(taskId + JSON_FILE_SUFFIX);
        MetadataFileWriter.write(metadataPath, metadataJson);
        if (comicId != null) {
            MetadataFileWriter.write(metadataDirectory.resolve(comicId + JSON_FILE_SUFFIX), metadataJson);
        }
        log.info("Metadata written: {}", metadataPath);
        return metadataPath;
    }
}
