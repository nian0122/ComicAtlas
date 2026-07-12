package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.parse.*;
import com.comicatlas.worker.file.storage.LocalStorageService;
import com.comicatlas.worker.event.CancelHandler;
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
    private final MetadataAssembler assembler;
    private final LocalStorageService storageService;
    private final ObjectMapper objectMapper;
    private final CancelHandler cancelHandler;

    /**
     * 统一导入：解析目录 → 解析后把图片搬到 hq/{comicId}/{chapterId}/ → 写 metadata。
     * ZIP 和 Directory 来源走同一逻辑。
     */
    public Path handle(ImportContext ctx, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        DirectoryTree tree = parser.parse(ctx.sourcePath());
        ComicMetadata metadata = assembler.assemble(tree, ctx);

        if (cancelHandler.isCancelled(taskId)) {
            log.info("Task cancelled after parse: taskId={}", taskId);
            throw new RuntimeException("Task cancelled: " + taskId);
        }

        // 搬文件到 HQ
        Path importRoot = tree.path();
        for (var ch : metadata.chapters()) {
            for (var page : ch.pages()) {
                Path src = importRoot.resolve(ch.sourceDir()).resolve(page.imageName());
                if (!Files.exists(src)) src = importRoot.resolve(page.imageName());
                if (Files.exists(src) && page.fileSize() > 0) {
                    String relativePath = comicId + "/" + ch.globalOrder() + "/" + page.imageName();
                    storageService.store(src, "HQ", relativePath);
                }
            }
        }

        // 封面
        var firstCh = metadata.chapters().get(0);
        if (!firstCh.pages().isEmpty()) {
            Path firstImg = storageService.resolve(new com.comicatlas.worker.file.storage.StorageRef("HQ",
                comicId + "/" + firstCh.globalOrder() + "/" + firstCh.pages().get(0).imageName()));
            if (Files.exists(firstImg)) {
                Path thumbsDir = mangaRoot.resolve("thumbs").resolve(String.valueOf(comicId));
                Files.createDirectories(thumbsDir);
                Files.copy(firstImg, thumbsDir.resolve("cover.webp"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return writeMetadata(metadata, taskId, mangaRoot);
    }

    private Path writeMetadata(ComicMetadata metadata, Long taskId, Path mangaRoot) throws Exception {
        Path metaPath = mangaRoot.resolve("metadata").resolve(taskId + ".json");
        Files.createDirectories(metaPath.getParent());

        Map<String, Object> comic = new LinkedHashMap<>();
        comic.put("title", metadata.title());
        comic.put("author", metadata.author() != null ? metadata.author() : "");
        comic.put("tags", metadata.tags());

        List<Map<String, Object>> catalogList = metadata.catalogs().stream().map(cat -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("title", cat.title());
            cm.put("sortOrder", cat.sortOrder());
            cm.put("parentIndex", cat.parentIndex());
            return cm;
        }).toList();

        List<Map<String, Object>> chapterList = metadata.chapters().stream().map(ch -> {
            Map<String, Object> chm = new LinkedHashMap<>();
            chm.put("title", ch.title());
            chm.put("chapterNo", ch.chapterNo());
            chm.put("sortOrder", ch.sortOrder());
            chm.put("globalOrder", ch.globalOrder());
            chm.put("catalogIndex", ch.catalogIndex());
            chm.put("sourceDir", ch.sourceDir());
            chm.put("pages", ch.pages().stream().map(p -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("imageName", p.imageName());
                pm.put("pageNumber", p.pageNumber());
                pm.put("hqStatus", p.hqStatus());
                pm.put("lqStatus", p.lqStatus());
                pm.put("fileSize", p.fileSize());
                if (p.width() != null) pm.put("width", p.width());
                if (p.height() != null) pm.put("height", p.height());
                return pm;
            }).toList());
            return chm;
        }).toList();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("comic", comic);
        root.put("catalogs", catalogList);
        root.put("chapters", chapterList);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), root);
        log.info("Metadata written: {}", metaPath);
        return metaPath;
    }
}
