package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.parse.ComicMetadata;
import com.comicatlas.worker.file.parse.DirectoryParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryImportHandler {

    private final DirectoryParser parser;
    private final ObjectMapper objectMapper;

    public Path importDirectory(String sourcePath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path comicDir = Path.of(sourcePath);
        ComicMetadata metadata = parser.parse(comicDir);

        Path metadataPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metadataPath.getParent());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("comic", Map.of(
            "title", metadata.title(),
            "author", metadata.author() != null ? metadata.author() : "",
            "tags", metadata.tags()
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
}
