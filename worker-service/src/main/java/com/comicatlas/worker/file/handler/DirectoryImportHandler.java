package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.file.manifest.ImportManifest;
import com.comicatlas.worker.file.manifest.ImportManifestManager;
import com.comicatlas.worker.file.parse.ComicMetadata;
import com.comicatlas.worker.file.parse.DirectoryParser;
import com.comicatlas.worker.file.parse.DirectoryTree;
import com.comicatlas.worker.file.parse.ImportContext;
import com.comicatlas.worker.file.parse.MetadataAssembler;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageService;
import com.comicatlas.worker.file.storage.TransferMode;
import com.comicatlas.worker.file.transcode.VideoNormalizer;
import com.comicatlas.worker.image.ImageOptimizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryImportHandler {

    private final DirectoryParser parser;
    private final MetadataAssembler assembler;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final ImageOptimizer imageOptimizer;
    private final CancelHandler cancelHandler;
    private final VideoNormalizer videoNormalizer;
    private final ImportManifestManager manifestManager;

    /**
     * 统一导入：清单驱动的安全搬运。
     * 清单存在 → 中断恢复（跳过已搬文件，metadata 从清单出，绝不重新解析源目录）；
     * 清单不存在 → 全新导入（标准化 → 解析 → 组装 → 写清单 → 搬文件）。
     */
    public Path handle(ImportContext ctx, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        ImportManifest manifest;
        if (manifestManager.exists(mangaRoot, taskId)) {
            manifest = manifestManager.read(mangaRoot, taskId);
            log.info("恢复中断导入: taskId={}, files={}", taskId, manifest.files().size());
        } else {
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

            // 构建清单（相对路径），原子写入后再动文件
            Path importRoot = tree.path();
            ManifestBuildResult manifestBuildResult = buildManifestFiles(metadata, comicId, importRoot);
            List<ImportManifest.ImportFile> files = manifestBuildResult.files();
            Map<String, String> generatedNames = manifestBuildResult.nameMap();
            JsonNode metadataNode = objectMapper.valueToTree(buildMetadataMap(metadata, comicId, generatedNames));
            manifest = new ImportManifest(1, taskId, ctx.sourceType(), importRoot.toString(),
                    metadataNode, files);
            manifestManager.write(mangaRoot, taskId, manifest);
            log.info("清单已写入: taskId={}, files={}", taskId, files.size());
        }

        // 按清单搬文件（含恢复跳过）
        Path sourceRoot = Path.of(manifest.sourceRoot());
        for (ImportManifest.ImportFile file : manifest.files()) {
            if (cancelHandler.isCancelled(taskId)) {
                log.info("Task cancelled during file move: taskId={}", taskId);
                throw new RuntimeException("Task cancelled: " + taskId);
            }
            Path source = sourceRoot.resolve(file.source());
            StorageRef storageRef = new StorageRef("HQ", file.target());
            Path destination = storageService.resolve(storageRef);
            if (Files.exists(destination)) {
                long destinationSize = Files.size(destination);
                if (destinationSize == file.size()) {
                    log.debug("跳过已搬文件: {}", destination);
                    continue;
                }
                throw new IOException("目标已存在但大小不匹配: " + destination
                        + " expected=" + file.size() + " actual=" + destinationSize);
            }
            if (!Files.exists(source)) {
                throw new IOException("源文件缺失且目标不存在: " + source);
            }
            storageService.transfer(source, storageRef, TransferMode.MOVE);
        }

        // 封面：从 metadata 读取首张图片（跳过 VIDEO），从 HQ 生成，不依赖源目录
        generateCoverFromNode(manifest.metadata(), comicId);

        // metadata.json 从清单 metadata 写出
        Path metaPath = writeMetadataNode(manifest.metadata(), taskId, comicId, mangaRoot);

        // 成功后清理恢复点
        manifestManager.delete(mangaRoot, taskId);
        return metaPath;
    }

    /**
     * 清单构建结果：文件列表 + 源文件名→生成文件名映射（用于 metadata）。
     */
    private record ManifestBuildResult(List<ImportManifest.ImportFile> files, Map<String, String> nameMap) {}

    private ManifestBuildResult buildManifestFiles(ComicMetadata metadata, Long comicId, Path importRoot) {
        List<ImportManifest.ImportFile> files = new ArrayList<>();
        Map<String, String> nameMap = new LinkedHashMap<>();
        for (var chapter : metadata.chapters()) {
            for (var page : chapter.pages()) {
                Path source = importRoot.resolve(chapter.sourceDir()).resolve(page.fileName());
                if (!Files.exists(source)) { source = importRoot.resolve(page.fileName()); }
                if (Files.exists(source) && page.fileSize() > 0) {
                    String relative = importRoot.relativize(source).toString().replace('\\', '/');
                    // 新布局：serverGeneratedName（UUID + 扩展名），目录暂用 globalOrder
                    String generatedName = generateServerName(page.fileName());
                    String target = comicId + "/" + chapter.globalOrder() + "/" + generatedName;
                    files.add(new ImportManifest.ImportFile(relative, target, page.fileSize()));
                    // QA 修复注记（task-21）：nameMap 键必须用相对路径而非裸 fileName，
                    // 否则多章节含同名文件（001.jpg）时后处理章节覆盖前者，
                    // 导致 metadata.hq_path 与清单存储路径不一致（文件搬进 hq/{globalOrder}/，
                    // 但 DB 页面路径指向另一章节的目录，LQ/HQ 删除按 hq_path 定位文件全部失败）。
                    nameMap.put(relative, target);
                }
            }
        }
        return new ManifestBuildResult(files, nameMap);
    }

    /**
     * 生成服务端文件名：{@code UUID + 原扩展名}。
     * 避免文件名冲突和路径猜解。
     */
    private static String generateServerName(String originalName) {
        String extension = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                extension = originalName.substring(dot).toLowerCase();
            }
        }
        return UUID.randomUUID().toString() + extension;
    }

    private Map<String, Object> buildMetadataMap(ComicMetadata metadata, Long comicId, Map<String, String> generatedNames) {
        Map<String, Object> comic = new LinkedHashMap<>();
        comic.put("title", metadata.title());
        comic.put("author", metadata.author() != null ? metadata.author() : "");
        comic.put("tags", metadata.tags());

        List<Map<String, Object>> catalogList = metadata.catalogs().stream().map(catalog -> {
            Map<String, Object> catalogMap = new LinkedHashMap<>();
            catalogMap.put("title", catalog.title());
            catalogMap.put("sortOrder", catalog.sortOrder());
            catalogMap.put("parentIndex", catalog.parentIndex());
            return catalogMap;
        }).toList();

        List<Map<String, Object>> chapterList = metadata.chapters().stream().map(chapter -> {
            Map<String, Object> chapterMap = new LinkedHashMap<>();
            chapterMap.put("title", chapter.title());
            chapterMap.put("chapterNo", chapter.chapterNo());
            chapterMap.put("sortOrder", chapter.sortOrder());
            chapterMap.put("globalOrder", chapter.globalOrder());
            chapterMap.put("catalogIndex", chapter.catalogIndex());
            chapterMap.put("sourceDir", chapter.sourceDir());
            chapterMap.put("mediaItems", chapter.pages().stream().map(page -> {
                Map<String, Object> mediaMap = new LinkedHashMap<>();
                mediaMap.put("fileName", page.fileName());
                mediaMap.put("pageNumber", page.pageNumber());
                mediaMap.put("hqStatus", page.hqStatus());
                mediaMap.put("lqStatus", page.lqStatus());
                mediaMap.put("fileSize", page.fileSize());
                // 新布局：写入 hqPath = {comicId}/{globalOrder}/{generatedName}
                String relKey = (chapter.sourceDir() != null && !chapter.sourceDir().isBlank())
                        ? chapter.sourceDir() + "/" + page.fileName()
                        : page.fileName();
                String generatedPath = generatedNames.get(relKey);
                if (generatedPath != null) {
                    mediaMap.put("hqPath", generatedPath);
                }
                if (page.width() != null) { mediaMap.put("width", page.width()); }
                if (page.height() != null) { mediaMap.put("height", page.height()); }
                mediaMap.put("mediaType", page.mediaType());
                if (page.duration() != null) { mediaMap.put("duration", page.duration()); }
                if (page.container() != null) { mediaMap.put("container", page.container()); }
                if (page.videoCodec() != null) { mediaMap.put("videoCodec", page.videoCodec()); }
                if (page.audioCodec() != null) { mediaMap.put("audioCodec", page.audioCodec()); }
                return mediaMap;
            }).toList());
            return chapterMap;
        }).toList();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 3);
        root.put("comic", comic);
        root.put("catalogs", catalogList);
        root.put("chapters", chapterList);
        return root;
    }

    private Path writeMetadataNode(JsonNode metadata, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        Path metaDir = mangaRoot.resolve("metadata");
        Files.createDirectories(metaDir);
        // 任务级 metadata（ImportEventHandler 消费）
        Path metaPath = metaDir.resolve(taskId + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), metadata);
        // QA 修复注记（task-21）：同时写出 comicId.json。
        // RecoveryEngine 按 metadata/{comicId}.json 查找元数据重建 DB 记录，而原实现
        // 只写 {taskId}.json，导致正常导入的漫画在 DB 数据丢失后恢复走占位路径
        // （RECOVERY_REQUIRED）而非完整恢复（READY）。
        if (comicId != null) {
            Path comicMeta = metaDir.resolve(comicId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(comicMeta.toFile(), metadata);
        }
        log.info("Metadata written: {}", metaPath);
        return metaPath;
    }

    private void generateCoverFromNode(JsonNode metadata, Long comicId) {
        JsonNode chapters = metadata.path("chapters");
        if (chapters.isEmpty()) { return; }
        JsonNode firstChapter = chapters.get(0);
        JsonNode mediaItems = firstChapter.path("mediaItems");
        if (mediaItems.isEmpty()) { return; }

        // 跳过 VIDEO 首项，找第一张图片 — 优先使用 hqPath
        JsonNode firstImage = null;
        for (JsonNode item : mediaItems) {
            if (!"VIDEO".equals(item.path("mediaType").asText())) {
                firstImage = item;
                break;
            }
        }
        if (firstImage != null) {
            String hqPath = firstImage.has("hqPath") ? firstImage.path("hqPath").asText() : null;
            if (hqPath == null || hqPath.isBlank()) {
                int globalOrder = firstChapter.path("globalOrder").asInt();
                hqPath = comicId + "/" + globalOrder + "/" + firstImage.path("fileName").asText();
            }
            Path firstImagePath = storageService.resolve(new StorageRef("HQ", hqPath));
            if (Files.exists(firstImagePath)) {
                try {
                    imageOptimizer.generateCover(comicId, firstImagePath);
                } catch (Exception e) {
                    log.error("封面生成失败: comicId={}, {}", comicId, e.getMessage());
                }
            }
        } else {
            JsonNode firstVideo = mediaItems.get(0);
            String hqPath = firstVideo.has("hqPath") ? firstVideo.path("hqPath").asText() : null;
            if (hqPath == null || hqPath.isBlank()) {
                int globalOrder = firstChapter.path("globalOrder").asInt();
                hqPath = comicId + "/" + globalOrder + "/" + firstVideo.path("fileName").asText();
            }
            Path firstVideoFile = storageService.resolve(new StorageRef("HQ", hqPath));
            if (Files.exists(firstVideoFile)) {
                try {
                    imageOptimizer.generateCoverFromVideo(comicId, firstVideoFile);
                } catch (Exception e) {
                    log.error("视频封面生成失败: comicId={}, {}", comicId, e.getMessage());
                }
            }
        }
    }
}
