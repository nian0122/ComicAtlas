package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.file.parse.*;
import com.comicatlas.worker.file.storage.LocalStorageService;
import com.comicatlas.worker.file.transcode.VideoNormalizer;
import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.image.ImageOptimizer;
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
    private final ImageOptimizer imageOptimizer;
    private final CancelHandler cancelHandler;
    private final VideoNormalizer videoNormalizer;

    /**
     * 统一导入：标准化视频 → 解析目录 → 搬文件到 hq/{comicId}/{chapterId}/ → 写 metadata。
     * ZIP 和 Directory 来源走同一逻辑。
     */
    public Path handle(ImportContext ctx, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        int normalized = videoNormalizer.normalize(ctx.sourcePath());
        if (normalized > 0) {
            log.info("视频标准化: {} 个文件已转码为 .mp4", normalized);
        }

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
                if (cancelHandler.isCancelled(taskId)) {
                    log.info("Task cancelled during file copy: taskId={}", taskId);
                    throw new RuntimeException("Task cancelled: " + taskId);
                }
                Path src = importRoot.resolve(ch.sourceDir()).resolve(page.fileName());
                if (!Files.exists(src)) src = importRoot.resolve(page.fileName());
                if (Files.exists(src) && page.fileSize() > 0) {
                    String relativePath = comicId + "/" + ch.globalOrder() + "/" + page.fileName();
                    storageService.store(src, "HQ", relativePath);
                }
            }
        }

        // 封面：跳过 VIDEO 首项，找第一张图片；调用 ImageOptimizer 生成优化封面
        var firstCh = metadata.chapters().get(0);
        var firstImage = firstCh.pages().stream()
                .filter(p -> !"VIDEO".equals(p.mediaType()))
                .findFirst()
                .orElse(null);
        if (firstImage != null) {
            Path firstImg = storageService.resolve(new com.comicatlas.worker.file.storage.StorageRef("HQ",
                comicId + "/" + firstCh.globalOrder() + "/" + firstImage.fileName()));
            if (Files.exists(firstImg)) {
                try {
                    imageOptimizer.generateCover(comicId, firstImg);
                } catch (Exception e) {
                    log.error("封面生成失败: comicId={}, {}", comicId, e.getMessage());
                }
            }
        } else {
            // 全视频漫画：从第一个视频抽帧做封面
            var firstVideo = firstCh.pages().stream()
                    .filter(p -> "VIDEO".equals(p.mediaType()))
                    .findFirst()
                    .orElse(null);
            if (firstVideo != null) {
                Path firstVideoFile = storageService.resolve(new com.comicatlas.worker.file.storage.StorageRef("HQ",
                    comicId + "/" + firstCh.globalOrder() + "/" + firstVideo.fileName()));
                if (Files.exists(firstVideoFile)) {
                    try {
                        imageOptimizer.generateCoverFromVideo(comicId, firstVideoFile);
                    } catch (Exception e) {
                        log.error("视频封面生成失败: comicId={}, {}", comicId, e.getMessage());
                    }
                }
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
            chm.put("mediaItems", ch.pages().stream().map(p -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("fileName", p.fileName());
                pm.put("pageNumber", p.pageNumber());
                pm.put("hqStatus", p.hqStatus());
                pm.put("lqStatus", p.lqStatus());
                pm.put("fileSize", p.fileSize());
                if (p.width() != null) pm.put("width", p.width());
                if (p.height() != null) pm.put("height", p.height());
                pm.put("mediaType", p.mediaType());
                if (p.duration() != null) pm.put("duration", p.duration());
                if (p.container() != null) pm.put("container", p.container());
                if (p.videoCodec() != null) pm.put("videoCodec", p.videoCodec());
                if (p.audioCodec() != null) pm.put("audioCodec", p.audioCodec());
                return pm;
            }).toList());
            return chm;
        }).toList();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 3);
        root.put("comic", comic);
        root.put("catalogs", catalogList);
        root.put("chapters", chapterList);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), root);
        log.info("Metadata written: {}", metaPath);
        return metaPath;
    }

    /**
     * v2 旧版元数据写入（保留以作参考）。
     * 当前 DirectoryImportHandler 仅生成 v3 格式；ImportEventHandler 仍按 v2 格式读取历史 metadata.json。
     * 若需重新生成 v2 兼容数据可临时启用此方法。
     */
    @SuppressWarnings("unused")
    private Path writeMetadataV2(ComicMetadata metadata, Long taskId, Path mangaRoot) throws Exception {
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
        log.info("Metadata written (v2): {}", metaPath);
        return metaPath;
    }
}
