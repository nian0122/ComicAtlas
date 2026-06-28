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

    /** Managed 模式：解析后把图片搬到 hq/{comicId}/{chapterNo}/ */
    public Path importManaged(String sourcePath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path inputDir = Path.of(sourcePath);
        Path comicRoot = parser.findComicRoot(inputDir);
        if (comicRoot == null) throw new RuntimeException("目录中没有图片: " + sourcePath);

        ComicMetadata metadata = parser.parse(comicRoot);

        for (var ch : metadata.chapters()) {
            Path hqDir = mangaRoot.resolve("hq").resolve(String.valueOf(comicId)).resolve(ch.chapterNo());
            Files.createDirectories(hqDir);
            Path sourceChapterDir = comicRoot.resolve(ch.title());
            if (!Files.exists(sourceChapterDir)) sourceChapterDir = comicRoot;

            for (var page : ch.pages()) {
                Path src = sourceChapterDir.resolve(page.imageName());
                if (Files.exists(src) && page.fileSize() > 0) {
                    Files.move(src, hqDir.resolve(page.imageName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        var firstCh = metadata.chapters().get(0);
        if (!firstCh.pages().isEmpty()) {
            Path firstImg = mangaRoot.resolve("hq").resolve(String.valueOf(comicId))
                .resolve(firstCh.chapterNo()).resolve(firstCh.pages().get(0).imageName());
            if (Files.exists(firstImg)) {
                Path thumbsDir = mangaRoot.resolve("thumbs").resolve(String.valueOf(comicId));
                Files.createDirectories(thumbsDir);
                Files.copy(firstImg, thumbsDir.resolve("cover.webp"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return writeMetadata(metadata, taskId, mangaRoot, "MANAGED", null, null);
    }

    /** External 模式：只写 metadata，不动文件 */
    public Path importExternal(String sourcePath, Long taskId, Long comicId, Path mangaRoot, String rootKey, Map<String,String> storageRoots) throws Exception {
        Path inputDir = Path.of(sourcePath);
        Path comicRoot = parser.findComicRoot(inputDir);
        if (comicRoot == null) throw new RuntimeException("目录中没有图片: " + sourcePath);

        ComicMetadata metadata = parser.parse(comicRoot);

        String rootPath = storageRoots.getOrDefault(rootKey, "");
        Path root = Path.of(rootPath);
        String relativePath = root.relativize(comicRoot).toString().replace('\\', '/') + "/";

        return writeMetadata(metadata, taskId, mangaRoot, "EXTERNAL", rootKey, relativePath);
    }

    private Path writeMetadata(ComicMetadata metadata, Long taskId, Path mangaRoot,
                                String storageType, String rootKey, String relativePath) throws Exception {
        Path metaPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metaPath.getParent());

        Map<String, Object> comic = new LinkedHashMap<>();
        comic.put("title", metadata.title());
        comic.put("author", metadata.author() != null ? metadata.author() : "");
        comic.put("tags", metadata.tags());
        comic.put("storageType", storageType);
        if (rootKey != null) comic.put("rootKey", rootKey);
        if (relativePath != null) comic.put("relativePath", relativePath);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comic", comic);
        m.put("chapters", metadata.chapters().stream().map(ch -> Map.of(
            "title", ch.title(), "chapterNo", ch.chapterNo(),
            "pages", ch.pages().stream().map(p -> Map.of(
                "imageName", p.imageName(), "pageNumber", p.pageNumber(),
                "hqStatus", p.hqStatus(), "lqStatus", p.lqStatus(),
                "fileSize", p.fileSize(), "width", p.width(), "height", p.height()
            )).toList()
        )).toList());

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), m);
        log.info("Metadata ({}) written: {}", storageType, metaPath);
        return metaPath;
    }
}
