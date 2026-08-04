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
            ManifestBuildResult mbr = buildManifestFiles(metadata, comicId, importRoot);
            List<ImportManifest.ImportFile> files = mbr.files();
            Map<String, String> generatedNames = mbr.nameMap();
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
            Path src = sourceRoot.resolve(file.source());
            StorageRef ref = new StorageRef("HQ", file.target());
            Path dst = storageService.resolve(ref);
            if (Files.exists(dst)) {
                long dstSize = Files.size(dst);
                if (dstSize == file.size()) {
                    log.debug("跳过已搬文件: {}", dst);
                    continue;
                }
                throw new IOException("目标已存在但大小不匹配: " + dst
                        + " expected=" + file.size() + " actual=" + dstSize);
            }
            if (!Files.exists(src)) {
                throw new IOException("源文件缺失且目标不存在: " + src);
            }
            storageService.transfer(src, ref, TransferMode.MOVE);
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
        for (var ch : metadata.chapters()) {
            for (var page : ch.pages()) {
                Path src = importRoot.resolve(ch.sourceDir()).resolve(page.fileName());
                if (!Files.exists(src)) src = importRoot.resolve(page.fileName());
                if (Files.exists(src) && page.fileSize() > 0) {
                    String relative = importRoot.relativize(src).toString().replace('\\', '/');
                    // 新布局：serverGeneratedName（UUID + 扩展名），目录暂用 globalOrder
                    String generatedName = generateServerName(page.fileName());
                    String target = comicId + "/" + ch.globalOrder() + "/" + generatedName;
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
        String ext = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                ext = originalName.substring(dot).toLowerCase();
            }
        }
        return UUID.randomUUID().toString() + ext;
    }

    private Map<String, Object> buildMetadataMap(ComicMetadata metadata, Long comicId, Map<String, String> generatedNames) {
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
                // 新布局：写入 hqPath = {comicId}/{globalOrder}/{generatedName}
                String relKey = (ch.sourceDir() != null && !ch.sourceDir().isBlank())
                        ? ch.sourceDir() + "/" + p.fileName()
                        : p.fileName();
                String generatedPath = generatedNames.get(relKey);
                if (generatedPath != null) {
                    pm.put("hqPath", generatedPath);
                }
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
        if (chapters.isEmpty()) return;
        JsonNode firstCh = chapters.get(0);
        JsonNode mediaItems = firstCh.path("mediaItems");
        if (mediaItems.isEmpty()) return;

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
                int globalOrder = firstCh.path("globalOrder").asInt();
                hqPath = comicId + "/" + globalOrder + "/" + firstImage.path("fileName").asText();
            }
            Path firstImg = storageService.resolve(new StorageRef("HQ", hqPath));
            if (Files.exists(firstImg)) {
                try {
                    imageOptimizer.generateCover(comicId, firstImg);
                } catch (Exception e) {
                    log.error("封面生成失败: comicId={}, {}", comicId, e.getMessage());
                }
            }
        } else {
            JsonNode firstVideo = mediaItems.get(0);
            String hqPath = firstVideo.has("hqPath") ? firstVideo.path("hqPath").asText() : null;
            if (hqPath == null || hqPath.isBlank()) {
                int globalOrder = firstCh.path("globalOrder").asInt();
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
