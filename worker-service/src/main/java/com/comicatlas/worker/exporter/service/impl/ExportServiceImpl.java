package com.comicatlas.worker.exporter.service.impl;

import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.exporter.resolver.ExportFileResolver;
import com.comicatlas.worker.exporter.publisher.ExportArchivePublisher;
import com.comicatlas.worker.exporter.archive.ZipBuilder;
import com.comicatlas.worker.exporter.archive.ExportStagingCleanup;
import com.comicatlas.worker.exporter.exception.ExportFileNotFoundException;
import com.comicatlas.worker.exporter.exception.ExportManifestBuildException;
import com.comicatlas.worker.exporter.model.ExportCollectResult;
import com.comicatlas.worker.exporter.model.ExportManifest;
import com.comicatlas.worker.exporter.metadata.ComicInfoXmlBuilder;
import com.comicatlas.worker.exporter.metadata.MetadataJsonExporter;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.worker.shared.common.ComicTitleSanitizer;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.persistence.record.CatalogRecord;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import com.comicatlas.worker.exporter.service.ExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** 导出编排：收集 → 构建清单 → 打包 ZIP → 原子发布任务目录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {
    // Worker 通过 MQ 暴露导出命令契约，具体实现保持在导出业务包内。

    /** 导出错误码：ZIP 打包失败（classifyExportError 契约值）。 */
    private static final String ERROR_CODE_ZIP = "ZIP_ERROR";
    /** 导出错误码：媒体收集失败。 */
    private static final String ERROR_CODE_COLLECT = "COLLECT_ERROR";
    /** 导出错误码：清单构建失败。 */
    private static final String ERROR_CODE_MANIFEST = "MANIFEST_ERROR";
    /** 导出错误码：存储访问失败。 */
    private static final String ERROR_CODE_STORAGE = "STORAGE_ERROR";
    /** 导出错误码：未归类失败。 */
    private static final String ERROR_CODE_EXPORT = "EXPORT_ERROR";
    /** staging 目录名前缀（任务发布前的临时目录）。 */
    private static final String STAGING_DIR_PREFIX = ".staging-";
    /** ZIP/CBZ 产物扩展名。 */
    private static final String ZIP_EXTENSION = ".zip";
    private static final String CBZ_EXTENSION = ".cbz";
    /** 导出文件名时间戳格式。 */
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ExportCollector exportCollector;
    private final ExportFileResolver exportFileResolver;
    private final ZipBuilder zipBuilder;
    private final MetadataJsonExporter metadataJsonExporter;
    private final StorageProperties storageProperties;
    private final WorkerConfig workerConfig;
    private final ExportArchivePublisher archivePublisher;
    /** 每个 Worker 顺序执行磁盘密集型导出，重投不得同时清理同一任务的 staging。 */
    private final ReentrantLock exportLock = new ReentrantLock(true);

    public ExportOutput export(Long comicId, Long taskId) throws IOException {
        return export(comicId, taskId, ExportFormats.ZIP);
    }

    public ExportOutput export(Long comicId, Long taskId, String format) throws IOException {
        try {
            exportLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("等待导出执行时被中断");
            interrupted.initCause(exception);
            throw interrupted;
        }
        try {
            return exportExclusive(comicId, taskId, format);
        } finally {
            exportLock.unlock();
        }
    }

    private ExportOutput exportExclusive(Long comicId, Long taskId, String format) throws IOException {
        long started = System.nanoTime();
        ExportCollectResult result = exportCollector.collect(comicId);
        ExportManifest manifest = buildManifest(result);
        log.info("导出清单就绪：taskId={}, comicId={}, entries={}, collectMs={}", taskId, comicId,
                manifest.entries().size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));

        StorageRoot exportRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.EXPORT);
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
        }

        String baseFileName = buildOutputFileName(comicId, result.comic().getTitle(), format);
        Path stagingDir = exportRoot.resolve(STAGING_DIR_PREFIX + taskId);
        Path finalDir = exportRoot.resolve(String.valueOf(taskId));
        ExportStagingCleanup.delete(stagingDir);
        try {
            Optional<ExportArchivePublisher.PublishResult> existing = archivePublisher.reuseIfPresent(
                    taskId, finalDir, manifest);
            if (existing.isPresent()) {
                return new ExportOutput(taskId, comicId, existing.get().fileName(), existing.get().size());
            }
            zipBuilder.build(manifest, stagingDir.resolve(baseFileName));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("导出发布前被中断");
            }
            ExportArchivePublisher.PublishResult published = archivePublisher.publish(
                    taskId, stagingDir, finalDir, manifest);
            return new ExportOutput(taskId, comicId, published.fileName(), published.size());
        } catch (IOException | RuntimeException exception) {
            // 覆盖打包、校验与发布失败；最终目录始终由发布器单独管理。
            ExportStagingCleanup.afterFailure(stagingDir, exception);
            throw exception;
        }
    }

    /** 供 handler 发失败事件使用。 */
    public String classifyExportError(Exception exception) {
        String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        if (message.contains("ZIP") || message.contains("zip")) {
            return ERROR_CODE_ZIP;
        }
        if (message.contains("collect") || message.contains("Collect")) {
            return ERROR_CODE_COLLECT;
        }
        if (message.contains("manifest") || message.contains("Manifest")) {
            return ERROR_CODE_MANIFEST;
        }
        if (message.contains("STORAGE") || message.contains("storage") || message.contains("EXPORT")) {
            return ERROR_CODE_STORAGE;
        }
        return ERROR_CODE_EXPORT;
    }

    /**
     * 构建导出清单 — 将收集结果转换为 ZIP 打包所需的结构化清单。
     *
     * <p>清单是严格契约：任一数据库媒体没有可用且可读的普通文件（缺失、目录冒充、
     * 不可读、读取大小失败）立即抛 {@link ExportManifestBuildException} 使整个导出失败，
     * 不跳过、不告警。重复或大小写折叠后冲突的 ZIP 目标路径同样拒绝；metadata UTF-8 字节
     * 与全部媒体未压缩总量使用 {@link Math#addExact} 累加，超过 maxEntrySize/maxTotalSize
     * 时在调用 ZipBuilder 之前失败。
     *
     * <p>目录层级由 catalog.parentId 与 chapter.catalogId 还原；章节目录名不再为了
     * 避免冲突而改写，以确保导出结果保留导入时的目录名。异常消息只携带
     * comicId/mediaId 与相对 targetPath，不输出宿主机绝对路径。
     */
    private ExportManifest buildManifest(ExportCollectResult result) {
        Long comicId = result.comic().getId();
        String rootDirName = ComicTitleSanitizer.sanitize(result.comic().getTitle());

        List<ExportManifest.Entry> entries = new ArrayList<>();
        Map<Long, List<MediaRecord>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(MediaRecord::getChapterId));

        Map<Long, String> catalogPaths = buildCatalogPaths(result.catalogs(), comicId);

        // 构建文件条目：按目录树分组，并做目标路径冲突与容量预检。
        Set<String> usedTargetPaths = new HashSet<>();
        long mediaTotalSize = 0L;
        for (ChapterRecord chapter : result.chapters()) {
            String chapterDirectory = buildChapterDirectory(chapter, catalogPaths, comicId);

            List<MediaRecord> chapterMedia = mediaByChapter.getOrDefault(chapter.getId(), List.of());
            List<MediaRecord> sortedMedia = chapterMedia.stream()
                    .sorted(Comparator.comparing(MediaRecord::getPageNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (MediaRecord media : sortedMedia) {
                ExportManifest.Entry entry = buildEntry(comicId, media, chapterDirectory, usedTargetPaths);
                entries.add(entry);
                mediaTotalSize = addSizes(comicId, media.getId(), mediaTotalSize, entry.sourceSize());
            }
        }
        if (entries.size() != result.allMedia().size()) {
            throw new ExportManifestBuildException(
                    "导出清单构建失败：comicId=" + comicId + ", 媒体条目数与采集数不一致: entries="
                            + entries.size() + ", allMedia=" + result.allMedia().size());
        }

        String metadataJson = metadataJsonExporter.exportJson(result);
        long metadataBytes = metadataJson.getBytes(StandardCharsets.UTF_8).length;
        long totalBytes = addSizes(comicId, null, mediaTotalSize, metadataBytes);
        if (totalBytes > maxTotalSize()) {
            throw new ExportManifestBuildException(
                    "导出清单构建失败：comicId=" + comicId + ", 导出总量超限: " + totalBytes
                            + " 字节 > maxTotalSize=" + maxTotalSize());
        }
        String comicInfoXml = ComicInfoXmlBuilder.build(result.comic(), result.chapters());
        long comicInfoBytes = comicInfoXml.getBytes(StandardCharsets.UTF_8).length;
        long exportBytesWithComicInfo = addSizes(comicId, null, totalBytes, comicInfoBytes);
        if (exportBytesWithComicInfo > maxTotalSize()) {
            throw new ExportManifestBuildException(
                    "导出清单构建失败：comicId=" + comicId + ", ComicInfo.xml 加入后导出总量超限: "
                            + exportBytesWithComicInfo + " 字节 > maxTotalSize=" + maxTotalSize());
        }
        return new ExportManifest(rootDirName, metadataJson, comicInfoXml, entries);
    }

    /** 根据 catalog 的父子关系构建每个目录的 ZIP 相对路径。 */
    private Map<Long, String> buildCatalogPaths(List<CatalogRecord> catalogs, Long comicId) {
        Map<Long, CatalogRecord> catalogsById = new HashMap<>(catalogs.size());
        for (CatalogRecord catalog : catalogs) {
            if (catalog.getId() == null || catalogsById.put(catalog.getId(), catalog) != null) {
                throw new ExportManifestBuildException("导出清单构建失败：comicId=" + comicId + ", 目录 ID 重复或缺失");
            }
        }
        Map<Long, String> paths = new HashMap<>(catalogs.size());
        for (CatalogRecord catalog : catalogs) {
            resolveCatalogPath(catalog, catalogsById, paths, new HashSet<>(), comicId);
        }
        return paths;
    }

    private String resolveCatalogPath(CatalogRecord catalog, Map<Long, CatalogRecord> catalogsById,
                                      Map<Long, String> paths, Set<Long> visitingCatalogIds, Long comicId) {
        String cachedPath = paths.get(catalog.getId());
        if (cachedPath != null) {
            return cachedPath;
        }
        if (!visitingCatalogIds.add(catalog.getId())) {
            throw new ExportManifestBuildException("导出清单构建失败：comicId=" + comicId + ", 目录层级存在循环");
        }
        String directoryName = requireDirectoryName(catalog.getTitle(), "目录", catalog.getId(), comicId);
        String path = directoryName;
        if (catalog.getParentId() != null) {
            CatalogRecord parent = catalogsById.get(catalog.getParentId());
            if (parent == null) {
                throw new ExportManifestBuildException("导出清单构建失败：comicId=" + comicId
                        + ", 目录父节点不存在: catalogId=" + catalog.getId());
            }
            path = resolveCatalogPath(parent, catalogsById, paths, visitingCatalogIds, comicId) + "/" + directoryName;
        }
        visitingCatalogIds.remove(catalog.getId());
        paths.put(catalog.getId(), path);
        return path;
    }

    /**
     * 章节作为目录树的叶子。混合目录（自身有媒体且有子目录）在导入时会同时
     * 创建同名 Catalog 和 Chapter；此处不重复追加章节名，令媒体回到该目录本身。
     */
    private String buildChapterDirectory(ChapterRecord chapter, Map<Long, String> catalogPaths, Long comicId) {
        String chapterDirectory = requireDirectoryName(chapter.getTitle(), "章节", chapter.getId(), comicId);
        if (chapter.getCatalogId() == null) {
            return chapterDirectory;
        }
        String catalogPath = catalogPaths.get(chapter.getCatalogId());
        if (catalogPath == null) {
            throw new ExportManifestBuildException("导出清单构建失败：comicId=" + comicId
                    + ", 章节关联目录不存在: chapterId=" + chapter.getId());
        }
        String catalogName = catalogPath.substring(catalogPath.lastIndexOf('/') + 1);
        if (catalogName.equals(chapterDirectory)) {
            return catalogPath;
        }
        return catalogPath + "/" + chapterDirectory;
    }

    private String requireDirectoryName(String name, String type, Long id, Long comicId) {
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new ExportManifestBuildException("导出清单构建失败：comicId=" + comicId
                    + ", " + type + "名称非法: id=" + id);
        }
        return name;
    }

    /**
     * 构建单个媒体条目 — 解析源文件并做可用性预检。
     *
     * @throws ExportManifestBuildException 无可用文件、文件不可读/非普通文件、大小超限或目标路径冲突
     */
    private ExportManifest.Entry buildEntry(Long comicId, MediaRecord media, String uniqueDir,
                                            Set<String> usedTargetPaths) {
        Long mediaId = media.getId();
        StorageRef storageRef;
        try {
            storageRef = exportFileResolver.resolve(media);
        } catch (ExportFileNotFoundException ex) {
            throw manifestBuildError(comicId, mediaId, "无可用媒体文件（HQ 缺失且 LQ 未就绪）", ex);
        }

        Path sourceFile = exportFileResolver.resolveToPath(storageRef);
        if (!Files.isRegularFile(sourceFile)) {
            throw manifestBuildError(comicId, mediaId, "源文件缺失或非普通文件: " + storageRef.relativePath());
        }
        if (!Files.isReadable(sourceFile)) {
            throw manifestBuildError(comicId, mediaId, "源文件不可读: " + storageRef.relativePath());
        }

        long sourceSize;
        try {
            sourceSize = Files.size(sourceFile);
        } catch (IOException ex) {
            throw manifestBuildError(comicId, mediaId, "读取源文件大小失败: " + storageRef.relativePath(), ex);
        }
        if (sourceSize > maxEntrySize()) {
            throw manifestBuildError(comicId, mediaId, "单文件超限: " + sourceSize
                    + " 字节 > maxEntrySize=" + maxEntrySize());
        }

        String fileName = Path.of(storageRef.relativePath()).getFileName().toString();
        String targetPath = normalizeTargetPath(uniqueDir + "/" + fileName);
        String foldedPath = targetPath.toLowerCase(Locale.ROOT);
        if (!usedTargetPaths.add(foldedPath)) {
            throw manifestBuildError(comicId, mediaId, "ZIP 目标路径冲突（大小写折叠后）: " + targetPath);
        }
        return new ExportManifest.Entry(targetPath, sourceFile, sourceSize);
    }

    private static String normalizeTargetPath(String targetPath) {
        String normalized = targetPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private long addSizes(Long comicId, Long mediaId, long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw new ExportManifestBuildException(
                    "导出清单构建失败：comicId=" + comicId + ", mediaId=" + mediaId + ", 媒体总量累加溢出", ex);
        }
    }

    private ExportManifestBuildException manifestBuildError(Long comicId, Long mediaId, String reason) {
        return manifestBuildError(comicId, mediaId, reason, null);
    }

    private ExportManifestBuildException manifestBuildError(Long comicId, Long mediaId, String reason, Throwable cause) {
        String message = "导出清单构建失败：comicId=" + comicId + ", mediaId=" + mediaId + ", " + reason;
        return cause == null ? new ExportManifestBuildException(message) : new ExportManifestBuildException(message, cause);
    }

    private long maxEntrySize() {
        return workerConfig.getZip().getMaxEntrySize();
    }

    private long maxTotalSize() {
        return workerConfig.getZip().getMaxTotalSize();
    }

    private String buildOutputFileName(Long comicId, String title, String format) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeTitle = ComicTitleSanitizer.sanitize(title);
        String extension = ExportFormats.CBZ.equalsIgnoreCase(format) ? CBZ_EXTENSION : ZIP_EXTENSION;
        return safeTitle + "_" + comicId + "_" + timestamp + extension;
    }
}
