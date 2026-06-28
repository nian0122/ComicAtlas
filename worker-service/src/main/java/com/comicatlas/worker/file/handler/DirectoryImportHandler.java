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

        // 把图片搬到 /manga/hq/{comicId}/{chapterNo}/
        for (var ch : metadata.chapters()) {
            Path hqChapterDir = mangaRoot.resolve("hq").resolve(String.valueOf(comicId)).resolve(ch.chapterNo());
            Files.createDirectories(hqChapterDir);
            for (var page : ch.pages()) {
                Path src = comicDir.resolve(page.imageName());
                if (Files.exists(src)) {
                    Files.move(src, hqChapterDir.resolve(page.imageName()), StandardCopyOption.REPLACE_EXISTING);
                }
                // 如果是章节结构，图片在子目录里
                Path srcInChapter = comicDir.resolve(ch.title()).resolve(page.imageName());
                if (Files.exists(srcInChapter)) {
                    Files.move(srcInChapter, hqChapterDir.resolve(page.imageName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // 生成封面缩略图
        if (!metadata.chapters().isEmpty() && !metadata.chapters().get(0).pages().isEmpty()) {
            Path firstPage = mangaRoot.resolve("hq").resolve(String.valueOf(comicId))
                .resolve(metadata.chapters().get(0).chapterNo())
                .resolve(metadata.chapters().get(0).pages().get(0).imageName());
            if (Files.exists(firstPage)) {
                Path thumbsDir = mangaRoot.resolve("thumbs").resolve(String.valueOf(comicId));
                Files.createDirectories(thumbsDir);
                Files.copy(firstPage, thumbsDir.resolve("cover.webp"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path metadataPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metadataPath.getParent());
        // ... rest of metadata writing

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
