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
        Path inputDir = Path.of(sourcePath);

        // 找到真正的漫画根目录（可能深层嵌套）
        Path comicRoot = parser.findComicRoot(inputDir);
        if (comicRoot == null) throw new RuntimeException("目录中没有图片: " + sourcePath);

        ComicMetadata metadata = parser.parse(comicRoot);

        // 把图片搬到 /manga/hq/{comicId}/{chapterNo}/
        for (var ch : metadata.chapters()) {
            Path hqDir = mangaRoot.resolve("hq").resolve(String.valueOf(comicId)).resolve(ch.chapterNo());
            Files.createDirectories(hqDir);
            Path sourceChapterDir = comicRoot.resolve(ch.title());
            if (!Files.exists(sourceChapterDir)) sourceChapterDir = comicRoot;

            for (var page : ch.pages()) {
                Path src = sourceChapterDir.resolve(page.imageName());
                if (Files.exists(src)) {
                    Files.move(src, hqDir.resolve(page.imageName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // 封面缩略图
        var firstCh = metadata.chapters().get(0);
        if (!firstCh.pages().isEmpty()) {
            Path hqDir = mangaRoot.resolve("hq").resolve(String.valueOf(comicId)).resolve(firstCh.chapterNo());
            Path firstImg = hqDir.resolve(firstCh.pages().get(0).imageName());
            if (Files.exists(firstImg)) {
                Path thumbsDir = mangaRoot.resolve("thumbs").resolve(String.valueOf(comicId));
                Files.createDirectories(thumbsDir);
                Files.copy(firstImg, thumbsDir.resolve("cover.webp"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // 写 metadata.json
        Path metaPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metaPath.getParent());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comic", Map.of("title", metadata.title(), "author", nvl(metadata.author()), "tags", metadata.tags()));
        m.put("chapters", metadata.chapters().stream().map(ch -> Map.of(
            "title", ch.title(), "chapterNo", ch.chapterNo(),
            "pages", ch.pages().stream().map(p -> Map.of(
                "imageName", p.imageName(), "pageNumber", p.pageNumber(),
                "hqStatus", p.hqStatus(), "lqStatus", p.lqStatus(),
                "fileSize", p.fileSize(), "width", p.width(), "height", p.height()
            )).toList()
        )).toList());

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), m);
        log.info("Import done: comicId={}, chapters={}, meta={}", comicId, metadata.chapters().size(), metaPath);
        return metaPath;
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
