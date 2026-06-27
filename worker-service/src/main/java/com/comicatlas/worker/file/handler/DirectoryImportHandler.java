package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.parse.ComicMetadata;
import com.comicatlas.worker.file.parse.DirectoryParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryImportHandler {

    private final DirectoryParser parser;
    private final ObjectMapper objectMapper;

    /**
     * 导入单个目录（平铺图片或章节结构）
     * @return metadata.json 的文件路径
     */
    public Path importDirectory(String sourcePath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path comicDir = Path.of(sourcePath);
        String rootKey = detectRootKey(sourcePath);
        ComicMetadata metadata = parser.parse(comicDir, rootKey);

        // 写入 metadata.json
        Path metadataPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metadataPath.getParent());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("comic", Map.of(
            "title", metadata.title(),
            "author", metadata.author() != null ? metadata.author() : "",
            "tags", metadata.tags(),
            "storageType", metadata.storageType(),
            "rootKey", metadata.rootKey(),
            "relativePath", metadata.relativePath()
        ));
        meta.put("chapters", metadata.chapters().stream().map(ch -> Map.of(
            "title", ch.title(),
            "chapterNo", ch.chapterNo(),
            "pages", ch.pages().stream().map(p -> Map.of(
                "imageName", p.imageName(),
                "pageNumber", p.pageNumber(),
                "hqStatus", p.hqStatus(),
                "lqStatus", p.lqStatus(),
                "fileSize", p.fileSize(),
                "width", p.width(),
                "height", p.height()
            )).toList()
        )).toList());

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), meta);
        log.info("Metadata written: {}", metadataPath);
        return metadataPath;
    }

    private String detectRootKey(String path) {
        String normalized = path.replace('\\', '/').toLowerCase();
        if (normalized.contains("games/comics")) return "LOCAL";
        return "LOCAL";
    }
}
