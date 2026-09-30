package com.comicatlas.worker.exporter.service.impl;

import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.exporter.resolver.ExportFileResolver;
import com.comicatlas.worker.exporter.publisher.ExportArchivePublisher;
import com.comicatlas.worker.exporter.archive.ZipBuilder;
import com.comicatlas.worker.exporter.archive.ExportStagingCleanup;
import com.comicatlas.worker.exporter.archive.BatchDirectoryMoveCheckpoint;
import com.comicatlas.worker.exporter.exception.ExportFileNotFoundException;
import com.comicatlas.worker.exporter.exception.ExportManifestBuildException;
import com.comicatlas.worker.exporter.exception.ExportMoveOutException;
import com.comicatlas.worker.exporter.model.ExportCollectResult;
import com.comicatlas.worker.exporter.model.DirectoryExportPlan;
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
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
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

/** 导出编排：单本 ZIP/CBZ 归档，或批量文件夹目录移动。 */
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
        StorageRoot exportRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.EXPORT);
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
        }
        ExportCollectResult result = exportCollector.collect(comicId);
        ExportManifest manifest = buildManifest(result);
        log.info("导出清单就绪：taskId={}, comicId={}, entries={}, collectMs={}", taskId, comicId,
                manifest.entries().size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));

        String baseFileName = buildOutputFileName(comicId, manifest.rootDirName(), format);
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

    /** 批量文件夹导出：整批预检通过后，以章节目录为单位移动媒体目录。 */
    @Override
    public ExportOutput exportBatchDirectory(List<Long> comicIds, Long taskId) throws IOException {
        try {
            exportLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("等待批量导出执行时被中断");
            interrupted.initCause(exception);
            throw interrupted;
        }
        try {
            return exportBatchDirectoryExclusive(comicIds, taskId);
        } finally {
            exportLock.unlock();
        }
    }

    private ExportOutput exportBatchDirectoryExclusive(List<Long> comicIds, Long taskId) throws IOException {
        if (comicIds == null || comicIds.isEmpty() || comicIds.stream().anyMatch(java.util.Objects::isNull)
                || comicIds.stream().distinct().count() != comicIds.size()) {
            throw new IOException("批量文件夹导出漫画列表为空或包含重复项");
        }
        StorageRoot exportRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.EXPORT);
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IOException("EXPORT 存储根未配置或路径不存在");
        }
        Path checkpointPath = exportRoot.resolve(".batch-move-" + taskId + ".checkpoint");
        Path stagingDir = exportRoot.resolve(STAGING_DIR_PREFIX + taskId);
        Path finalDir = exportRoot.resolve(String.valueOf(taskId));
        List<DirectoryExportPlan> plans;
        if (Files.isRegularFile(checkpointPath, LinkOption.NOFOLLOW_LINKS)) {
            plans = loadBatchMoveCheckpoint(checkpointPath, taskId);
            if (!plans.stream().map(DirectoryExportPlan::comicId).toList().equals(comicIds)) {
                throw new ExportMoveOutException("批量导出检查点与任务漫画列表不一致 taskId=" + taskId,
                        new IOException("检查点漫画列表不匹配"));
            }
            validateBatchDirectoryPlans(plans, stagingDir);
            if (!Files.exists(finalDir, LinkOption.NOFOLLOW_LINKS)) {
                for (DirectoryExportPlan plan : plans) {
                    requireSameFileStore(exportRoot, plan.sourceRootKey(), plan.comicId(), true);
                }
            }
        } else {
            plans = buildBatchDirectoryPlans(comicIds);
            validateBatchDirectoryPlans(plans, stagingDir);
            for (DirectoryExportPlan plan : plans) {
                requireSameFileStore(exportRoot, plan.sourceRootKey(), plan.comicId(), false);
            }
            BatchDirectoryMoveCheckpoint.save(checkpointPath, plans);
        }

        if (Files.exists(finalDir, LinkOption.NOFOLLOW_LINKS)) {
            verifyPublishedBatchDirectory(finalDir, plans, taskId);
            for (DirectoryExportPlan plan : plans) {
                detachManagedDirectories(plan.comicId(), plan.sourceRootKey(), taskId);
            }
            return batchOutput(taskId, plans);
        }

        int movedDirectoryCount = 0;
        try {
            Files.createDirectories(stagingDir);
            for (DirectoryExportPlan plan : plans) {
                for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("批量目录移动被中断");
                    }
                    Path sourceDirectory = sourcePath(plan.sourceRootKey(), move.sourceRelativePath());
                    Path targetDirectory = stagingDir.resolve(validatedRelativePath(move.targetRelativePath())).normalize();
                    if (!targetDirectory.startsWith(stagingDir.toAbsolutePath().normalize())) {
                        throw new IOException("批量导出目标路径越界");
                    }
                    boolean sourceExists = Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS);
                    boolean targetExists = Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS);
                    if (!sourceExists && Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (!sourceExists || !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(sourceDirectory) || targetExists) {
                        throw new IOException("章节目录移动源或目标状态冲突：comicId=" + plan.comicId()
                                + ", chapterId=" + move.chapterId());
                    }
                    Files.createDirectories(targetDirectory.getParent());
                    try {
                        Files.move(sourceDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(sourceDirectory, targetDirectory);
                    }
                    movedDirectoryCount++;
                }
                for (String directory : plan.catalogDirectories()) {
                    Path targetDirectory = stagingDir.resolve(validatedRelativePath(directory)).normalize();
                    if (!targetDirectory.startsWith(stagingDir.toAbsolutePath().normalize())) {
                        throw new IOException("批量导出目录结构越界");
                    }
                    Files.createDirectories(targetDirectory);
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (movedDirectoryCount > 0 || hasMovedBatchDirectory(plans, stagingDir)) {
                throw new ExportMoveOutException("批量目录部分移动，保留检查点供重试 taskId=" + taskId, exception);
            }
            ExportStagingCleanup.afterFailure(stagingDir, exception);
            try {
                Files.deleteIfExists(checkpointPath);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }

        try {
            Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ExportMoveOutException("批量目录已整理但原子发布不可用，保留暂存目录供重试 taskId=" + taskId,
                    exception);
        } catch (IOException exception) {
            throw new ExportMoveOutException("批量目录已整理但发布失败，保留暂存目录供重试 taskId=" + taskId,
                    exception);
        }
        for (DirectoryExportPlan plan : plans) {
            detachManagedDirectories(plan.comicId(), plan.sourceRootKey(), taskId);
        }
        return batchOutput(taskId, plans);
    }

    private List<DirectoryExportPlan> buildBatchDirectoryPlans(List<Long> comicIds) throws IOException {
        List<ExportCollectResult> results = new ArrayList<>(comicIds.size());
        for (Long comicId : comicIds) {
            ExportCollectResult result = exportCollector.collect(comicId);
            if (result.allMedia().isEmpty()) {
                throw new IOException("选中漫画没有可导出的媒体：comicId=" + comicId);
            }
            results.add(result);
        }
        Map<Long, String> comicDirectoryNames = uniqueComicDirectoryNames(results);
        List<DirectoryExportPlan> plans = new ArrayList<>(results.size());
        for (ExportCollectResult result : results) {
            Long comicId = result.comic().getId();
            plans.add(buildDirectoryPlan(comicId, comicDirectoryNames.get(comicId), result));
        }
        return List.copyOf(plans);
    }

    private Map<Long, String> uniqueComicDirectoryNames(List<ExportCollectResult> results) {
        Map<Long, String> names = new HashMap<>(results.size());
        Set<String> usedNames = new HashSet<>();
        for (ExportCollectResult result : results) {
            Long comicId = result.comic().getId();
            String baseName = sanitizeComicFolderName(result.comic().getTitle(), comicId);
            String candidate = baseName;
            if (usedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
                candidate = baseName + "_" + comicId;
            }
            int suffix = 2;
            while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = baseName + "_" + comicId + "_" + suffix++;
            }
            names.put(comicId, candidate);
        }
        return names;
    }

    private String sanitizeComicFolderName(String title, Long comicId) {
        String safeTitle = ComicTitleSanitizer.sanitize(title == null ? "" : title)
                .replaceAll("[\\x00-\\x1F]", "").replaceAll("[. ]+$", "");
        if (safeTitle.isBlank()) {
            safeTitle = "漫画_" + comicId;
        }
        String reservedName = safeTitle.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
                "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
                "LPT7", "LPT8", "LPT9").contains(reservedName)) {
            return safeTitle + "_" + comicId;
        }
        return safeTitle;
    }

    /** 只根据漫画、目录、章节和媒体数据库记录生成移动计划，不逐文件扫描或移动。 */
    private DirectoryExportPlan buildDirectoryPlan(Long comicId, String rootDirName, ExportCollectResult result)
            throws IOException {
        String sourceRootKey = selectDirectorySourceRoot(comicId);
        Map<Long, String> catalogPaths = buildCatalogPaths(result.catalogs(), comicId);
        Map<Long, List<MediaRecord>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(MediaRecord::getChapterId));
        Map<Long, String> sourceDirectoriesByChapter = new HashMap<>(result.chapters().size());
        Map<Long, String> chapterDirectories = new HashMap<>(result.chapters().size());
        Set<String> usedChapterDirectories = new HashSet<>();
        for (ChapterRecord chapter : result.chapters()) {
            String chapterDirectory = buildChapterDirectory(chapter, catalogPaths, comicId);
            if (result.chapters().size() == 1 && chapter.getCatalogId() == null
                    && chapterDirectory.equalsIgnoreCase(sanitizeComicFolderName(result.comic().getTitle(), comicId))) {
                // 单章节漫画以漫画名作为章节名时，图片原本就在漫画根目录，避免重复创建同名子目录。
                chapterDirectory = "";
            }
            if (!usedChapterDirectories.add(chapterDirectory.toLowerCase(Locale.ROOT))) {
                throw new ExportManifestBuildException("文件夹导出失败：章节目录重名 comicId=" + comicId);
            }
            chapterDirectories.put(chapter.getId(), chapterDirectory);
            for (MediaRecord media : mediaByChapter.getOrDefault(chapter.getId(), List.of())) {
                Path mediaPath = Path.of(directoryMediaPath(media, sourceRootKey, comicId, chapter.getId()));
                String sourceDirectory = mediaPath.getParent().toString().replace('\\', '/');
                String previousSourceDirectory = sourceDirectoriesByChapter.putIfAbsent(
                        chapter.getId(), sourceDirectory);
                if (previousSourceDirectory != null && !previousSourceDirectory.equals(sourceDirectory)) {
                    throw new ExportManifestBuildException("文件夹导出失败：同一章节的媒体分布在多个源目录 comicId="
                            + comicId + ", chapterId=" + chapter.getId());
                }
            }
        }
        Set<String> usedMediaPaths = new HashSet<>();
        long estimatedSize = 0L;
        for (ChapterRecord chapter : result.chapters()) {
            String chapterDirectory = chapterDirectories.get(chapter.getId());
            for (MediaRecord media : mediaByChapter.getOrDefault(chapter.getId(), List.of())) {
                String sourceRelativePath = directoryMediaPath(media, sourceRootKey, comicId, chapter.getId());
                String mediaFileName = Path.of(sourceRelativePath).getFileName().toString();
                String targetPath = chapterDirectory.isEmpty() ? mediaFileName : chapterDirectory + "/" + mediaFileName;
                if (!usedMediaPaths.add(targetPath.toLowerCase(Locale.ROOT))) {
                    throw new ExportManifestBuildException("文件夹导出失败：媒体目标路径冲突 comicId=" + comicId);
                }
                long mediaSize = StorageRootKeys.LQ.equals(sourceRootKey)
                        ? (media.getLqSize() == null ? 0L : media.getLqSize())
                        : (media.getHqSize() == null ? 0L : media.getHqSize());
                estimatedSize = addSizes(comicId, media.getId(), estimatedSize, mediaSize);
            }
        }
        List<DirectoryExportPlan.ChapterMove> chapterMoves = new ArrayList<>();
        StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, sourceRootKey);
        if (sourceRoot == null || !sourceRoot.exists()) {
            throw new IOException("文件夹导出来源存储根不可用: " + sourceRootKey);
        }
        for (ChapterRecord chapter : result.chapters()) {
            List<MediaRecord> chapterMedia = mediaByChapter.getOrDefault(chapter.getId(), List.of());
            boolean hasMedia = !chapterMedia.isEmpty();
            String sourceRelativePath = sourceDirectoriesByChapter.getOrDefault(
                    chapter.getId(), comicId + "/" + chapter.getId());
            Path sourceDirectory = sourceRoot.resolve(sourceRelativePath);
            if (Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(sourceDirectory)) {
                    throw new IOException("章节源路径不是普通目录：chapterId=" + chapter.getId());
                }
                if (hasMedia) {
                    validateMediaOnlyDirectory(sourceDirectory, chapterMedia, sourceRootKey, comicId, chapter.getId());
                    String targetRelativePath = chapterDirectories.get(chapter.getId()).isEmpty()
                            ? rootDirName : rootDirName + "/" + chapterDirectories.get(chapter.getId());
                    chapterMoves.add(new DirectoryExportPlan.ChapterMove(chapter.getId(), sourceRelativePath,
                            targetRelativePath));
                } else if (hasDirectoryEntries(sourceDirectory)) {
                    throw new IOException("无媒体章节源目录包含未登记文件：comicId=" + comicId
                            + ", chapterId=" + chapter.getId());
                }
            } else if (hasMedia) {
                throw new IOException("章节目录缺失：chapterId=" + chapter.getId());
            }
        }
        Set<String> structureDirectories = new HashSet<>();
        catalogPaths.values().forEach(directory -> structureDirectories.add(rootDirName + "/" + directory));
        chapterDirectories.values().forEach(directory -> structureDirectories.add(
                directory.isEmpty() ? rootDirName : rootDirName + "/" + directory));
        return new DirectoryExportPlan(comicId, rootDirName, sourceRootKey,
                List.copyOf(structureDirectories), chapterMoves, estimatedSize);
    }

    private void validateMediaOnlyDirectory(Path sourceDirectory, List<MediaRecord> chapterMedia,
                                            String sourceRootKey, Long comicId, Long chapterId) throws IOException {
        Set<String> expectedFileNames = new HashSet<>();
        for (MediaRecord media : chapterMedia) {
            String relativePath = directoryMediaPath(media, sourceRootKey, comicId, chapterId);
            expectedFileNames.add(Path.of(relativePath).getFileName().toString());
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sourceDirectory)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)
                        || !expectedFileNames.remove(fileName)) {
                    throw new IOException("章节源目录包含非媒体文件或未登记文件：comicId=" + comicId
                            + ", chapterId=" + chapterId);
                }
            }
        }
        if (!expectedFileNames.isEmpty()) {
            throw new IOException("章节源目录缺少数据库登记的媒体文件：comicId=" + comicId + ", chapterId=" + chapterId);
        }
    }

    private String selectDirectorySourceRoot(Long comicId) throws IOException {
        StorageRoot hqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.HQ);
        if (hqRoot == null || !hqRoot.exists()) {
            throw new IOException("HQ 存储根未配置或不可用");
        }
        Path hqComicDirectory = hqRoot.resolve(String.valueOf(comicId));
        if (Files.exists(hqComicDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(hqComicDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(hqComicDirectory)) {
                throw new IOException("HQ 漫画路径不是普通目录");
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(hqComicDirectory)) {
                if (children.iterator().hasNext()) {
                    return StorageRootKeys.HQ;
                }
            }
        }
        StorageRoot lqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.LQ);
        if (lqRoot == null || !lqRoot.exists()) {
            throw new IOException("HQ 漫画目录为空且 LQ 存储根不可用");
        }
        Path lqComicDirectory = lqRoot.resolve(String.valueOf(comicId));
        if (!Files.isDirectory(lqComicDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lqComicDirectory)) {
            throw new IOException("HQ 漫画目录为空且 LQ 漫画目录不可用");
        }
        return StorageRootKeys.LQ;
    }

    private String directoryMediaPath(MediaRecord media, String sourceRootKey, Long comicId, Long chapterId) {
        boolean useLowQuality = StorageRootKeys.LQ.equals(sourceRootKey);
        String mediaType = media.getMediaType();
        String relativePath;
        if (useLowQuality) {
            if (!"IMAGE".equals(mediaType) || !"READY".equals(media.getLqStatus())) {
                throw new ExportManifestBuildException("HQ 目录为空，LQ 不包含全部漫画媒体：comicId="
                        + comicId + ", chapterId=" + chapterId + ", mediaId=" + media.getId());
            }
            relativePath = media.getLqPath();
        } else {
            if (!"READY".equals(media.getHqStatus())) {
                throw new ExportManifestBuildException("HQ 目录非空但漫画原件记录不完整：comicId="
                        + comicId + ", chapterId=" + chapterId + ", mediaId=" + media.getId());
            }
            relativePath = media.getHqPath();
        }
        String normalizedPath = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (normalizedPath.isBlank() || normalizedPath.startsWith("/") || normalizedPath.contains(":")) {
            throw new ExportManifestBuildException("文件夹导出媒体路径非法：mediaId=" + media.getId());
        }
        for (String pathSegment : normalizedPath.split("/", -1)) {
            if (pathSegment.isBlank() || ".".equals(pathSegment) || "..".equals(pathSegment)) {
                throw new ExportManifestBuildException("文件夹导出媒体路径非法：mediaId=" + media.getId());
            }
        }
        Path mediaPath = Path.of(normalizedPath);
        if (mediaPath.getNameCount() < 3 || !comicId.toString().equals(mediaPath.getName(0).toString())) {
            throw new ExportManifestBuildException("文件夹导出媒体路径不属于所选章节目录：mediaId=" + media.getId());
        }
        String fileName = mediaPath.getFileName().toString();
        if (fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new ExportManifestBuildException("文件夹导出媒体文件名非法：mediaId=" + media.getId());
        }
        return mediaPath.toString().replace('\\', '/');
    }

    private boolean hasDirectoryEntries(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return entries.iterator().hasNext();
        }
    }

    private List<DirectoryExportPlan> loadBatchMoveCheckpoint(Path checkpointPath, Long taskId)
            throws ExportMoveOutException {
        try {
            return BatchDirectoryMoveCheckpoint.load(checkpointPath);
        } catch (IOException | RuntimeException exception) {
            throw new ExportMoveOutException("批量目录移动检查点不可读，保留任务等待恢复 taskId=" + taskId, exception);
        }
    }

    private void validateBatchDirectoryPlans(List<DirectoryExportPlan> plans, Path stagingDir) throws IOException {
        Set<String> rootNames = new HashSet<>();
        for (DirectoryExportPlan plan : plans) {
            String rootName = plan.rootDirName();
            if (rootName.isBlank() || Path.of(rootName).isAbsolute() || Path.of(rootName).getNameCount() != 1
                    || ".".equals(rootName) || "..".equals(rootName)
                    || !rootNames.add(rootName.toLowerCase(Locale.ROOT))) {
                throw new IOException("批量文件夹导出漫画目录名非法或重复");
            }
            StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, plan.sourceRootKey());
            if (sourceRoot == null || !sourceRoot.exists()) {
                throw new IOException("文件夹导出来源存储根不可用");
            }
            Path comicDirectory = sourceRoot.resolve(String.valueOf(plan.comicId())).toAbsolutePath().normalize();
            for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
                Path relativeSource = validatedRelativePath(move.sourceRelativePath());
                Path source = sourceRoot.resolve(relativeSource.toString()).toAbsolutePath().normalize();
                Path target = validatedRelativePath(move.targetRelativePath());
                if (relativeSource.getNameCount() < 2
                        || !plan.comicId().toString().equals(relativeSource.getName(0).toString())
                        || !source.startsWith(comicDirectory) || source.equals(comicDirectory)
                        || target.isAbsolute() || target.getNameCount() < 1
                        || !rootName.equals(target.getName(0).toString()) || target.startsWith("..")) {
                    throw new IOException("批量目录移动计划包含越界源或目标路径");
                }
                validateDirectoryPathComponents(target);
            }
            Path normalizedStaging = stagingDir.toAbsolutePath().normalize();
            for (String directory : plan.catalogDirectories()) {
                Path relativeDirectory = validatedRelativePath(directory);
                if (!normalizedStaging.resolve(relativeDirectory).normalize().startsWith(normalizedStaging)) {
                    throw new IOException("批量目录移动计划包含越界结构目录");
                }
                validateDirectoryPathComponents(relativeDirectory);
            }
        }
    }

    private void validateDirectoryPathComponents(Path relativePath) throws IOException {
        for (Path component : relativePath) {
            String name = component.toString();
            String reservedName = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (name.isBlank() || name.endsWith(".") || name.endsWith(" ")
                    || name.matches(".*[<>:\"/\\\\|?*\\x00-\\x1F].*")
                    || Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
                    "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
                    "LPT7", "LPT8", "LPT9").contains(reservedName)) {
                throw new IOException("批量导出目录名称不受目标文件系统支持");
            }
        }
    }

    private void verifyPublishedBatchDirectory(Path finalDir, List<DirectoryExportPlan> plans, Long taskId)
            throws IOException {
        if (!Files.isDirectory(finalDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(finalDir)) {
            throw new IOException("批量导出发布路径不是普通目录 taskId=" + taskId);
        }
        for (DirectoryExportPlan plan : plans) {
            Path comicDirectory = finalDir.resolve(plan.rootDirName());
            if (!Files.isDirectory(comicDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new ExportMoveOutException("已发布批量导出缺少漫画目录 taskId=" + taskId
                        + ", comicId=" + plan.comicId(), new IOException("漫画目录不存在"));
            }
            for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
                Path target = finalDir.resolve(validatedRelativePath(move.targetRelativePath())).normalize();
                if (!target.startsWith(finalDir.toAbsolutePath().normalize())
                        || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ExportMoveOutException("已发布批量导出缺少章节目录 taskId=" + taskId
                            + ", chapterId=" + move.chapterId(), new IOException("章节目录不存在"));
                }
            }
        }
    }

    private boolean hasMovedBatchDirectory(List<DirectoryExportPlan> plans, Path stagingDir) {
        for (DirectoryExportPlan plan : plans) {
            for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
                if (!Files.exists(sourcePath(plan.sourceRootKey(), move.sourceRelativePath()), LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(stagingDir.resolve(move.targetRelativePath()), LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ExportOutput batchOutput(Long taskId, List<DirectoryExportPlan> plans) throws IOException {
        long totalSize = 0L;
        for (DirectoryExportPlan plan : plans) {
            totalSize = addSizes(plan.comicId(), null, totalSize, plan.estimatedSize());
        }
        return new ExportOutput(taskId, plans.getFirst().comicId(), String.valueOf(taskId), totalSize);
    }

    /** 原件和派生目录均按目录 rename 脱离管理；不递归删除目录内文件。 */
    private void detachManagedDirectories(Long comicId, String sourceRootKey, Long taskId)
            throws ExportMoveOutException {
        try {
            StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, sourceRootKey);
            if (sourceRoot == null) {
                throw new IOException("原件存储根不可用");
            }
            detachRemainingSourceDirectory(sourceRoot, comicId, taskId);
            detachGeneratedDirectory(StorageRootKeys.HQ, comicId, taskId, sourceRootKey);
            detachGeneratedDirectory(StorageRootKeys.LQ, comicId, taskId, sourceRootKey);
            detachGeneratedDirectory(StorageRootKeys.THUMBS, comicId, taskId, sourceRootKey);
            StorageRoot metadataRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.METADATA);
            if (metadataRoot != null && metadataRoot.exists()) {
                Files.deleteIfExists(metadataRoot.resolve(comicId + ".json"));
            }
        } catch (IOException | RuntimeException exception) {
            throw new ExportMoveOutException("原件目录移出未完成，保留导出产物并等待重试 comicId=" + comicId, exception);
        }
    }

    /** 未进入导出计划的残留数据（例如已删除章节）保留在脱管区，避免阻断已发布导出收尾。 */
    private void detachRemainingSourceDirectory(StorageRoot sourceRoot, Long comicId, Long taskId)
            throws IOException {
        Path source = sourceRoot.resolve(String.valueOf(comicId));
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("原件漫画路径不是普通目录: comicId=" + comicId);
        }
        if (!hasDirectoryEntries(source)) {
            Files.delete(source);
            return;
        }

        Path detachedParent = sourceRoot.resolve(".detached");
        Path detachedTarget = detachedParent.resolve(taskId + "-" + comicId);
        if (Files.exists(detachedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("原件残留脱管目录已存在: comicId=" + comicId);
        }
        Files.createDirectories(detachedParent);
        try {
            Files.move(source, detachedTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, detachedTarget);
        }
    }

    private void detachGeneratedDirectory(String rootKey, Long comicId, Long taskId, String sourceRootKey)
            throws IOException {
        if (rootKey.equals(sourceRootKey)) {
            return;
        }
        StorageRoot root = StorageRootResolver.optional(storageProperties, rootKey);
        if (root == null || !root.exists()) {
            return;
        }
        Path source = root.resolve(String.valueOf(comicId));
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("派生媒体漫画路径不是普通目录: " + rootKey);
        }
        Path detachedParent = root.resolve(".detached");
        Path target = detachedParent.resolve(taskId + "-" + comicId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("派生媒体脱管目录已存在: " + rootKey);
        }
        Files.createDirectories(detachedParent);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void requireSameFileStore(StorageRoot exportRoot, String sourceRootKey, Long comicId,
                                      boolean hasCheckpoint) throws IOException {
        StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, sourceRootKey);
        Path comicDirectory = sourceRoot == null ? null : sourceRoot.resolve(String.valueOf(comicId));
        try {
            if (sourceRoot == null || !sourceRoot.exists() || comicDirectory == null
                    || !Files.isDirectory(comicDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(comicDirectory)
                    || !Files.getFileStore(exportRoot.getPath()).equals(Files.getFileStore(comicDirectory))) {
                throw new IOException("目录移动要求来源存储根与 EXPORT 位于同一卷 comicId=" + comicId);
            }
        } catch (IOException exception) {
            if (hasCheckpoint) {
                throw new ExportMoveOutException("目录移动恢复被阻止：卷状态已变化 comicId=" + comicId, exception);
            }
            throw exception;
        }
    }

    private Path validatedRelativePath(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("相对路径为空");
        }
        Path path = Path.of(relativePath).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IOException("相对路径越界");
        }
        return path;
    }

    private Path sourcePath(String rootKey, String relativePath) {
        StorageRoot root = StorageRootResolver.optional(storageProperties, rootKey);
        return root.resolve(relativePath);
    }

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
     * 构建导出清单 — 将收集结果转换为归档或目录移动所需的结构化清单。
     *
     * <p>清单是严格契约：任一数据库媒体没有可用且可读的普通文件（缺失、目录冒充、
     * 不可读、读取大小失败）立即抛 {@link ExportManifestBuildException} 使整个导出失败，
     * 不跳过、不告警。重复或大小写折叠后冲突的目标路径同样拒绝；单文件限制适用于所有格式，
     * 归档总量限制仅用于 ZIP/CBZ，文件夹导出在写入前按目标卷空间预检。
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

    /** 根据 catalog 的父子关系构建每个目录的相对路径。 */
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
