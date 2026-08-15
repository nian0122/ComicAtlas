package com.comicatlas.worker.importer;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.util.MetadataFileWriter;
import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.image.CoverGenerator;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageService;
import com.comicatlas.worker.storage.TransferMode;
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

/**
 * 统一导入：清单驱动的安全搬运（导入两阶段最终化的<b>第一阶段（staging）</b>）。
 * <p>
 * <b>暂存语义</b>：Worker 在 DB 尚未生成章节 ID 时，以漫画内暂存键 {@code globalOrder} 把文件
 * 落到 {@code hq/{comicId}/{globalOrder}}（见 {@link #buildManifestFiles}）；最终目录
 * {@code hq/{comicId}/{chapterId}} 由 API 插入章节取得不可变 {@code chapterId} 后，经
 * {@code ImportStorageFinalizeRequestedEvent} 逐章请求 Worker 搬运（见
 * com.comicatlas.worker.event.ImportStorageFinalizeHandler）。
 * <p>
 * 清单存在 → 中断恢复（跳过已搬文件，metadata 从清单出，绝不重新解析源目录）；
 * 清单不存在 → 全新导入（标准化 → 解析 → 组装 → 写清单 → 搬文件）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryImportHandler {

    /** metadata V3 版本号（与 MetadataV3 模型一致）。 */
    private static final int METADATA_VERSION = 3;
    /** 导入清单版本号（与 ImportManifestManager.VERSION 保持一致）。 */
    private static final int MANIFEST_VERSION = 1;
    /** 媒体类型：视频。 */
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";
    /** 媒体类型：图片。 */
    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    /** metadata 目录名（MANGA_ROOT 下）。 */
    private static final String METADATA_DIR_NAME = "metadata";
    /** metadata JSON 文件名后缀。 */
    private static final String JSON_FILE_SUFFIX = ".json";

    private final DirectoryParser parser;
    private final MetadataAssembler assembler;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final CoverGenerator coverGenerator;
    private final CoverCandidateSelector coverCandidateSelector;
    private final CancelHandler cancelHandler;
    private final ImportManifestManager manifestManager;

    /**
     * 统一导入：清单驱动的安全搬运。
     * 清单存在 → 中断恢复（跳过已搬文件，metadata 从清单出，绝不重新解析源目录）；
     * 清单不存在 → 全新导入（标准化 → 解析 → 组装 → 写清单 → 搬文件）。
     */
    public Path handle(ImportContext ctx, Long taskId, Long comicId, Path mangaRoot) throws IOException {
        ImportManifest manifest;
        if (manifestManager.exists(mangaRoot, taskId)) {
            manifest = manifestManager.read(mangaRoot, taskId);
            log.info("恢复中断导入: taskId={}, files={}", taskId, manifest.files().size());
        } else {
            // 导入只录入文件信息 + 生成封面，不做视频转码/图片优化；
            // 转码与 LQ 优化由导入后在管理面板手动调用接口执行，加快导入时间。
            DirectoryTree tree = parser.parse(ctx.sourcePath(), ctx.sourceType());
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
            manifest = new ImportManifest(MANIFEST_VERSION, taskId, ctx.sourceType(), importRoot.toString(),
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
            StorageRef storageRef = new StorageRef(StorageRootKeys.HQ, file.target());
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

        // 注意：此处不再删除清单（恢复点）。两阶段最终化协议中，文件先按
        // {comicId}/{globalOrder} 暂存，Worker 逐章搬运到 {comicId}/{chapterId} 时
        // 仍需清单中的预期尺寸做幂等校验，并由 ImportStorageFinalizeHandler 在全部章节
        // 最终化完成后经 rewriteWithoutChapter 清空清单目录。若在此提前删除，
        // 最终化阶段将无法核对尺寸（sourceDir==targetDir 时还会静默跳过导致不发布
        // Completed，漫画卡在 IMPORTING）。
        return metaPath;
    }

    private ManifestBuildResult buildManifestFiles(ComicMetadata metadata, Long comicId, Path importRoot) {
        List<ImportManifest.ImportFile> files = new ArrayList<>();
        Map<String, String> nameMap = new LinkedHashMap<>();
        for (ComicMetadata.ChapterInfo chapter : metadata.chapters()) {
            for (ComicMetadata.MediaInfo page : chapter.pages()) {
                Path source = importRoot.resolve(chapter.sourceDir()).resolve(page.fileName());
                if (!Files.exists(source)) {
                    source = importRoot.resolve(page.fileName());
                }
                if (Files.exists(source) && page.fileSize() > 0) {
                    String relative = importRoot.relativize(source).toString().replace('\\', '/');
                    // 目标文件名保留原始文件名（禁止 UUID 化），目录用 globalOrder——
                    // DB chapterId 未生成前的漫画内暂存键，最终化时由 Worker 搬到 {comicId}/{chapterId}
                    String target = comicId + "/" + chapter.globalOrder() + "/" + page.fileName();
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
                // 新布局：写入 hqPath = {comicId}/{globalOrder}/{generatedName}（staging 暂存布局，
                // 最终化阶段由 Worker 按 {comicId}/{chapterId} 移动后以事件真实 targetDir 修正）
                String relKey = (chapter.sourceDir() != null && !chapter.sourceDir().isBlank())
                        ? chapter.sourceDir() + "/" + page.fileName()
                        : page.fileName();
                String generatedPath = generatedNames.get(relKey);
                if (generatedPath != null) {
                    mediaMap.put("hqPath", generatedPath);
                }
                if (page.width() != null) {
                    mediaMap.put("width", page.width());
                }
                if (page.height() != null) {
                    mediaMap.put("height", page.height());
                }
                mediaMap.put("mediaType", page.mediaType());
                if (page.duration() != null) {
                    mediaMap.put("duration", page.duration());
                }
                if (page.container() != null) {
                    mediaMap.put("container", page.container());
                }
                if (page.videoCodec() != null) {
                    mediaMap.put("videoCodec", page.videoCodec());
                }
                if (page.audioCodec() != null) {
                    mediaMap.put("audioCodec", page.audioCodec());
                }
                return mediaMap;
            }).toList());
            return chapterMap;
        }).toList();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", METADATA_VERSION);
        root.put("comic", comic);
        root.put("catalogs", catalogList);
        root.put("chapters", chapterList);
        return root;
    }

    private Path writeMetadataNode(JsonNode metadata, Long taskId, Long comicId, Path mangaRoot) throws IOException {
        Path metaDir = mangaRoot.resolve(METADATA_DIR_NAME);
        Files.createDirectories(metaDir);
        // 统一原子写（tmp → flush → ATOMIC_MOVE）：避免崩溃产生半截 metadata 无法用于恢复
        String metadataJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        // 任务级 metadata（ImportEventHandler 消费）
        Path metaPath = metaDir.resolve(taskId + JSON_FILE_SUFFIX);
        MetadataFileWriter.write(metaPath, metadataJson);
        // QA 修复注记（task-21）：同时写出 comicId.json。
        // RecoveryEngine 按 metadata/{comicId}.json 查找元数据重建 DB 记录，而原实现
        // 只写 {taskId}.json，导致正常导入的漫画在 DB 数据丢失后恢复走占位路径
        // （RECOVERY_REQUIRED）而非完整恢复（READY）。
        if (comicId != null) {
            Path comicMeta = metaDir.resolve(comicId + JSON_FILE_SUFFIX);
            MetadataFileWriter.write(comicMeta, metadataJson);
        }
        log.info("Metadata written: {}", metaPath);
        return metaPath;
    }

    /**
     * 封面生成：从清单 metadata 提取全部媒体项，交由 CoverCandidateSelector 按固定优先级排序，
     * 逐个候选尝试生成；单候选失败保留 cause 并继续下一候选，全部失败仅告警不阻断导入。
     */
    private void generateCoverFromNode(JsonNode metadata, Long comicId) {
        List<CoverCandidateSelector.MediaCandidate> media = flattenMedia(metadata, comicId);
        if (media.isEmpty()) {
            return;
        }
        List<CoverCandidateSelector.CoverCandidate> candidates = coverCandidateSelector.select(media);
        if (candidates.isEmpty()) {
            log.warn("无可用的封面候选，本漫画无封面: comicId={}", comicId);
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            CoverCandidateSelector.CoverCandidate candidate = candidates.get(i);
            Path sourcePath = storageService.resolve(new StorageRef(StorageRootKeys.HQ, candidate.hqPath()));
            if (!Files.exists(sourcePath)) {
                log.warn("封面候选文件缺失，跳过: comicId={}, candidateIndex={}, fileName={}",
                        comicId, i, candidate.fileName());
                continue;
            }
            try {
                if (MEDIA_TYPE_VIDEO.equalsIgnoreCase(candidate.mediaType())) {
                    coverGenerator.generateCoverFromVideo(comicId, sourcePath);
                } else {
                    coverGenerator.generateCover(comicId, sourcePath);
                }
                log.info("封面候选生成成功: comicId={}, candidateIndex={}, fileName={}",
                        comicId, i, candidate.fileName());
                return;
            } catch (RuntimeException ex) {
                log.warn("封面候选生成失败，继续下一候选: comicId={}, candidateIndex={}, fileName={}",
                        comicId, i, candidate.fileName(), ex);
            }
        }
        log.warn("全部封面候选生成失败，本漫画无封面: comicId={}, candidateCount={}",
                comicId, candidates.size());
    }

    /**
     * 把清单 metadata 展开为选择器输入；hqPath 缺失时按
     * {comicId}/{globalOrder}/{fileName} 兜底（与清单目标命名一致）。
     */
    private List<CoverCandidateSelector.MediaCandidate> flattenMedia(JsonNode metadata, Long comicId) {
        List<CoverCandidateSelector.MediaCandidate> media = new ArrayList<>();
        JsonNode chapters = metadata.path("chapters");
        for (JsonNode chapter : chapters) {
            int globalOrder = chapter.path("globalOrder").asInt();
            String sourceDir = chapter.hasNonNull("sourceDir") ? chapter.path("sourceDir").asText() : null;
            JsonNode mediaItems = chapter.path("mediaItems");
            for (JsonNode item : mediaItems) {
                String fileName = item.path("fileName").asText(null);
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                String mediaType = item.path("mediaType").asText(MEDIA_TYPE_IMAGE);
                int pageNumber = item.path("pageNumber").asInt();
                String hqPath = item.path("hqPath").asText(null);
                if (hqPath == null || hqPath.isBlank()) {
                    hqPath = comicId + "/" + globalOrder + "/" + fileName;
                }
                media.add(new CoverCandidateSelector.MediaCandidate(
                        mediaType, globalOrder, pageNumber, sourceDir, fileName, hqPath));
            }
        }
        return media;
    }

    /** 清单构建结果：文件列表 + 源文件名→生成文件名映射（用于 metadata）。 */
    private record ManifestBuildResult(List<ImportManifest.ImportFile> files, Map<String, String> nameMap) {
    }
}
