package com.comicatlas.worker.exporter.service.impl;

import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.exporter.resolver.ExportFileResolver;
import com.comicatlas.worker.exporter.publisher.ExportArchivePublisher;
import com.comicatlas.worker.exporter.archive.ZipBuilder;
import com.comicatlas.worker.exporter.archive.ExportStagingCleanup;
import com.comicatlas.worker.exporter.archive.DirectoryMoveCheckpoint;
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

/** 导出编排：收集 → 构建清单 → 打包归档或写出文件夹 → 原子发布任务目录。 */
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
        boolean isDirectoryExport = ExportFormats.DIRECTORY.equalsIgnoreCase(format);
        StorageRoot exportRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.EXPORT);
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
        }
        if (isDirectoryExport) {
            Optional<ExportOutput> publishedOutput = cleanupPublishedDirectoryExport(taskId, comicId, exportRoot);
            if (publishedOutput.isPresent()) {
                return publishedOutput.get();
            }
            Path moveCheckpoint = exportRoot.resolve(".moveout-" + taskId + ".checkpoint");
            DirectoryExportPlan plan;
            if (Files.isRegularFile(moveCheckpoint)) {
                plan = loadMoveCheckpoint(moveCheckpoint, taskId);
                requireSameFileStore(exportRoot, plan.sourceRootKey(), comicId, true);
            } else {
                ExportCollectResult result = exportCollector.collect(comicId);
                plan = buildDirectoryPlan(comicId, result);
                validateDirectoryMovePlan(comicId, plan, exportRoot.resolve(STAGING_DIR_PREFIX + taskId));
                requireSameFileStore(exportRoot, plan.sourceRootKey(), comicId, false);
                DirectoryMoveCheckpoint.save(moveCheckpoint, plan);
            }
            log.info("文件夹导出目录计划就绪：taskId={}, comicId={}, sourceRoot={}, chapters={}", taskId,
                    comicId, plan.sourceRootKey(), plan.chapterMoves().size());
            return exportDirectory(taskId, comicId, plan, exportRoot);
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

    /** 按章节目录直接移出原件，不逐文件枚举；完成后原子发布 staging。 */
    private ExportOutput exportDirectory(Long taskId, Long comicId, DirectoryExportPlan plan, StorageRoot exportRoot)
            throws IOException {
        Path stagingDir = exportRoot.resolve(STAGING_DIR_PREFIX + taskId);
        Path finalDir = exportRoot.resolve(String.valueOf(taskId));
        Path stagingRoot = stagingDir.resolve(plan.rootDirName());
        Path checkpointPath = exportRoot.resolve(".moveout-" + taskId + ".checkpoint");
        boolean hasCheckpoint = Files.isRegularFile(checkpointPath);
        boolean hadPriorMoves = plan.chapterMoves().stream().anyMatch(move ->
                !Files.exists(sourcePath(plan.sourceRootKey(), move.sourceRelativePath()), LinkOption.NOFOLLOW_LINKS));
        int movedDirectoryCount = 0;
        try {
            long metadataBytes = plan.metadataJson().getBytes(StandardCharsets.UTF_8).length;
            long comicInfoBytes = plan.comicInfoXml() == null ? 0
                    : plan.comicInfoXml().getBytes(StandardCharsets.UTF_8).length;
            long metadataTotalBytes = Math.addExact(metadataBytes, comicInfoBytes);
            long requiredBytes = Math.addExact(metadataTotalBytes,
                    workerConfig.getDirectoryExport().getMinimumFreeSpaceBytes());
            long availableBytes = Files.getFileStore(exportRoot.getPath()).getUsableSpace();
            if (availableBytes < requiredBytes) {
                throw new IOException("文件夹导出空间不足：元数据需要 " + metadataTotalBytes
                        + " 字节，安全余量 " + workerConfig.getDirectoryExport().getMinimumFreeSpaceBytes()
                        + " 字节，当前可用 " + availableBytes + " 字节");
            }
            Files.createDirectories(stagingRoot);
            writeDirectoryEntry(stagingRoot.resolve("metadata.json"),
                    plan.metadataJson().getBytes(StandardCharsets.UTF_8));
            if (plan.comicInfoXml() != null && !plan.comicInfoXml().isBlank()) {
                writeDirectoryEntry(stagingRoot.resolve("ComicInfo.xml"),
                        plan.comicInfoXml().getBytes(StandardCharsets.UTF_8));
            }

            List<DirectoryExportPlan.ChapterMove> orderedMoves = plan.chapterMoves().stream()
                    .sorted(Comparator.comparingInt(move -> Path.of(move.targetRelativePath()).getNameCount()))
                    .toList();
            for (DirectoryExportPlan.ChapterMove move : orderedMoves) {
                Path sourceDirectory = sourcePath(plan.sourceRootKey(), move.sourceRelativePath());
                Path targetDirectory = exportRoot.resolve(STAGING_DIR_PREFIX + taskId)
                        .resolve(move.targetRelativePath()).normalize();
                if (!targetDirectory.startsWith(stagingDir.toAbsolutePath().normalize())) {
                    throw new IOException("文件夹导出目标路径越界");
                }
                boolean sourceExists = Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS);
                boolean targetExists = Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS);
                if (!sourceExists && Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!sourceExists || !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(sourceDirectory) || targetExists) {
                    throw new IOException("章节目录移动源或目标状态冲突：chapterId=" + move.chapterId());
                }
                Files.createDirectories(targetDirectory.getParent());
                try {
                    Files.move(sourceDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(sourceDirectory, targetDirectory);
                }
                movedDirectoryCount++;
            }

            for (String catalogDirectory : plan.catalogDirectories()) {
                Path targetDirectory = stagingDir.resolve(catalogDirectory).normalize();
                if (!targetDirectory.startsWith(stagingDir.toAbsolutePath().normalize())) {
                    throw new IOException("文件夹导出目录层级越界");
                }
                Files.createDirectories(targetDirectory);
            }
        } catch (IOException | RuntimeException exception) {
            if (hasCheckpoint && (hadPriorMoves || movedDirectoryCount > 0)) {
                throw new ExportMoveOutException("章节目录部分移出，保留 staging 和检查点等待重试 taskId="
                        + taskId, exception);
            }
            ExportStagingCleanup.afterFailure(stagingDir, exception);
            if (hasCheckpoint) {
                try {
                    Files.deleteIfExists(checkpointPath);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }

        try {
            Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ExportMoveOutException("文件夹目录已整理，但原子发布暂不可用；保留 staging 供重试 taskId="
                    + taskId, exception);
        } catch (IOException exception) {
            throw new ExportMoveOutException("文件夹目录已整理，但发布失败；保留 staging 供重试 taskId="
                    + taskId, exception);
        }
        detachManagedDirectories(comicId, plan.sourceRootKey(), taskId);
        return new ExportOutput(taskId, comicId, taskId + "/" + plan.rootDirName(), plan.estimatedSize());
    }

    /** 只根据漫画、目录、章节和媒体数据库记录生成计划，不访问单个媒体文件。 */
    private DirectoryExportPlan buildDirectoryPlan(Long comicId, ExportCollectResult result) throws IOException {
        String rootDirName = ComicTitleSanitizer.sanitize(result.comic().getTitle());
        String sourceRootKey = selectDirectorySourceRoot(comicId);
        Map<Long, String> catalogPaths = buildCatalogPaths(result.catalogs(), comicId);
        Set<String> catalogDirectories = new HashSet<>(catalogPaths.values());
        Map<Long, List<MediaRecord>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(MediaRecord::getChapterId));
        Map<Long, String> chapterDirectories = new HashMap<>(result.chapters().size());
        Set<String> usedChapterDirectories = new HashSet<>();
        for (ChapterRecord chapter : result.chapters()) {
            String chapterDirectory = buildChapterDirectory(chapter, catalogPaths, comicId);
            if (!usedChapterDirectories.add(chapterDirectory.toLowerCase(Locale.ROOT))) {
                throw new ExportManifestBuildException("文件夹导出失败：章节目录重名 comicId=" + comicId);
            }
            chapterDirectories.put(chapter.getId(), chapterDirectory);
        }

        Map<Long, String> mediaTargetPaths = new HashMap<>(result.allMedia().size());
        Set<String> usedMediaPaths = new HashSet<>();
        long estimatedMediaBytes = 0L;
        for (ChapterRecord chapter : result.chapters()) {
            String chapterDirectory = chapterDirectories.get(chapter.getId());
            for (MediaRecord media : mediaByChapter.getOrDefault(chapter.getId(), List.of())) {
                String sourceRelativePath = directoryMediaPath(media, sourceRootKey, comicId, chapter.getId());
                String fileName = Path.of(sourceRelativePath).getFileName().toString();
                String targetPath = chapterDirectory + "/" + fileName;
                if (!usedMediaPaths.add(targetPath.toLowerCase(Locale.ROOT))) {
                    throw new ExportManifestBuildException("文件夹导出失败：媒体目标路径冲突 comicId=" + comicId);
                }
                mediaTargetPaths.put(media.getId(), targetPath);
                long mediaSize = StorageRootKeys.LQ.equals(sourceRootKey)
                        ? (media.getLqSize() == null ? 0L : media.getLqSize())
                        : (media.getHqSize() == null ? 0L : media.getHqSize());
                estimatedMediaBytes = addSizes(comicId, media.getId(), estimatedMediaBytes, mediaSize);
            }
        }

        String metadataJson = metadataJsonExporter.exportDirectoryJson(result, mediaTargetPaths);
        String comicInfoXml = ComicInfoXmlBuilder.build(result.comic(), result.chapters());
        long estimatedSize = addSizes(comicId, null, estimatedMediaBytes,
                metadataJson.getBytes(StandardCharsets.UTF_8).length);
        estimatedSize = addSizes(comicId, null, estimatedSize, comicInfoXml.getBytes(StandardCharsets.UTF_8).length);

        List<DirectoryExportPlan.ChapterMove> chapterMoves = new ArrayList<>();
        StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, sourceRootKey);
        if (sourceRoot == null || !sourceRoot.exists()) {
            throw new IOException("文件夹导出来源存储根不可用: " + sourceRootKey);
        }
        for (ChapterRecord chapter : result.chapters()) {
            Path sourceDirectory = sourceRoot.resolve(comicId + "/" + chapter.getId());
            boolean hasMedia = !mediaByChapter.getOrDefault(chapter.getId(), List.of()).isEmpty();
            if (Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(sourceDirectory)) {
                    throw new IOException("章节源路径不是普通目录：chapterId=" + chapter.getId());
                }
                chapterMoves.add(new DirectoryExportPlan.ChapterMove(chapter.getId(),
                        comicId + "/" + chapter.getId(), rootDirName + "/" + chapterDirectories.get(chapter.getId())));
            } else if (hasMedia) {
                throw new IOException("章节目录缺失：chapterId=" + chapter.getId());
            }
        }

        Set<String> structureDirectories = new HashSet<>();
        catalogDirectories.forEach(path -> structureDirectories.add(rootDirName + "/" + path));
        chapterDirectories.values().forEach(path -> structureDirectories.add(rootDirName + "/" + path));
        DirectoryExportPlan plan = new DirectoryExportPlan(rootDirName, sourceRootKey, metadataJson,
                comicInfoXml, List.copyOf(structureDirectories), chapterMoves, estimatedSize);
        validateDirectoryMovePlan(comicId, plan, null);
        return plan;
    }

    /** HQ 整本目录非空时统一使用 HQ；仅当 HQ 漫画目录没有直接子项时才回退整本到 LQ。 */
    private String selectDirectorySourceRoot(Long comicId) throws IOException {
        StorageRoot hqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.HQ);
        if (hqRoot == null || !hqRoot.exists()) {
            throw new IOException("HQ 存储根未配置或不可用");
        }
        Path hqComicDirectory = hqRoot.resolve(String.valueOf(comicId));
        if (Files.exists(hqComicDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(hqComicDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(hqComicDirectory)) {
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
        if (!Files.isDirectory(lqComicDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(lqComicDirectory)) {
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
        String expectedPrefix = comicId + "/" + chapterId + "/";
        if (!normalizedPath.startsWith(expectedPrefix)) {
            throw new ExportManifestBuildException("文件夹导出媒体路径不属于所选章节目录：mediaId=" + media.getId());
        }
        String fileName = normalizedPath.substring(expectedPrefix.length());
        if (fileName.isBlank() || fileName.contains("/") || ".".equals(fileName) || "..".equals(fileName)) {
            throw new ExportManifestBuildException("文件夹导出媒体文件名非法：mediaId=" + media.getId());
        }
        return expectedPrefix + fileName;
    }

    private DirectoryExportPlan loadMoveCheckpoint(Path checkpointPath, Long taskId) throws ExportMoveOutException {
        try {
            return DirectoryMoveCheckpoint.load(checkpointPath);
        } catch (IOException | RuntimeException exception) {
            throw new ExportMoveOutException("目录移动检查点不可读，保留任务等待人工恢复 taskId=" + taskId,
                    exception);
        }
    }

    private void validateDirectoryMovePlan(Long comicId, DirectoryExportPlan plan, Path stagingDir)
            throws IOException {
        if (plan.rootDirName().isBlank() || Path.of(plan.rootDirName()).isAbsolute()
                || Path.of(plan.rootDirName()).getNameCount() != 1
                || ".".equals(plan.rootDirName()) || "..".equals(plan.rootDirName())) {
            throw new IOException("文件夹导出漫画根目录名非法");
        }
        StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, plan.sourceRootKey());
        if (sourceRoot == null) {
            throw new IOException("文件夹导出来源存储根未配置");
        }
        Path comicDirectory = sourceRoot.resolve(String.valueOf(comicId)).toAbsolutePath().normalize();
        for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
            if (!move.sourceRelativePath().equals(comicId + "/" + move.chapterId())) {
                throw new IOException("目录移动检查点章节源路径与章节 ID 不符");
            }
            Path source = sourceRoot.resolve(move.sourceRelativePath()).toAbsolutePath().normalize();
            Path target = validatedRelativePath(move.targetRelativePath());
            if (!source.startsWith(comicDirectory) || !comicDirectory.equals(source.getParent())
                    || target.isAbsolute() || target.getNameCount() < 2
                    || !plan.rootDirName().equals(target.getName(0).toString())
                    || target.startsWith("..")) {
                throw new IOException("目录移动检查点包含越界源或目标路径");
            }
        }
        if (stagingDir != null) {
            Path normalizedStaging = stagingDir.toAbsolutePath().normalize();
            for (String directory : plan.catalogDirectories()) {
                Path target = normalizedStaging.resolve(validatedRelativePath(directory)).normalize();
                if (!target.startsWith(normalizedStaging)) {
                    throw new IOException("目录移动检查点包含越界目录路径");
                }
            }
        }
    }

    /** 已发布目录重投只检查章节目录级结构，不枚举其中的媒体文件。 */
    private Optional<ExportOutput> cleanupPublishedDirectoryExport(Long taskId, Long comicId, StorageRoot exportRoot)
            throws IOException {
        Path finalDir = exportRoot.resolve(String.valueOf(taskId));
        if (!Files.exists(finalDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path checkpoint = exportRoot.resolve(".moveout-" + taskId + ".checkpoint");
        if (!Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("文件夹导出发布冲突：缺少目录移动检查点 taskId=" + taskId);
        }
        if (!Files.isDirectory(finalDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(finalDir)) {
            throw new IOException("文件夹导出发布冲突：任务路径不是普通目录 taskId=" + taskId);
        }
        List<Path> roots;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(finalDir)) {
            roots = new ArrayList<>();
            for (Path child : children) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    roots.add(child);
                }
            }
        }
        if (roots.size() != 1) {
            throw new IOException("文件夹导出发布冲突：任务根目录数量不正确 taskId=" + taskId);
        }
        Path rootDirectory = roots.getFirst();
        DirectoryExportPlan plan = loadMoveCheckpoint(checkpoint, taskId);
        if (!rootDirectory.getFileName().toString().equals(plan.rootDirName())) {
            throw new IOException("文件夹导出发布冲突：漫画根目录名与检查点不符 taskId=" + taskId);
        }
        try {
            verifyMetadata(rootDirectory.resolve("metadata.json"), plan.metadataJson());
            if (plan.comicInfoXml() != null) {
                verifyMetadata(rootDirectory.resolve("ComicInfo.xml"), plan.comicInfoXml());
            }
            for (DirectoryExportPlan.ChapterMove move : plan.chapterMoves()) {
                Path target = rootDirectory.getParent().resolve(move.targetRelativePath()).normalize();
                if (!target.startsWith(rootDirectory.getParent().toAbsolutePath().normalize())
                        || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("已发布目录缺少章节目录：chapterId=" + move.chapterId());
                }
            }
        } catch (IOException exception) {
            throw new ExportMoveOutException("已发布目录结构校验失败，保留检查点等待恢复 taskId=" + taskId,
                    exception);
        }
        detachManagedDirectories(comicId, plan.sourceRootKey(), taskId);
        return Optional.of(new ExportOutput(taskId, comicId,
                taskId + "/" + plan.rootDirName(), plan.estimatedSize()));
    }

    private void verifyMetadata(Path path, String expectedValue) throws IOException {
        byte[] expected = expectedValue.getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != expected.length
                || !java.util.Arrays.equals(Files.readAllBytes(path), expected)) {
            throw new IOException("导出元数据与目录移动检查点不符");
        }
    }

    /** 原件和派生目录均按目录 rename 脱离管理；不递归删除目录内文件。 */
    private void detachManagedDirectories(Long comicId, String sourceRootKey, Long taskId)
            throws ExportMoveOutException {
        try {
            StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, sourceRootKey);
            if (sourceRoot == null) {
                throw new IOException("原件存储根不可用");
            }
            Files.deleteIfExists(sourceRoot.resolve(String.valueOf(comicId)));
            detachGeneratedDirectory(StorageRootKeys.HQ, comicId, taskId, sourceRootKey);
            detachGeneratedDirectory(StorageRootKeys.LQ, comicId, taskId, sourceRootKey);
            detachGeneratedDirectory(StorageRootKeys.THUMBS, comicId, taskId, sourceRootKey);
            StorageRoot metadataRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.METADATA);
            if (metadataRoot != null && metadataRoot.exists()) {
                Files.deleteIfExists(metadataRoot.resolve(comicId + ".json"));
            }
        } catch (IOException | RuntimeException exception) {
            throw new ExportMoveOutException("原件目录移出未完成，保留导出产物并等待重试 comicId=" + comicId,
                    exception);
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

    /** 目录 rename 必须同卷；HQ 空时改用 LQ 后也按所选根检查卷。 */
    private void requireSameFileStore(StorageRoot exportRoot, String sourceRootKey, Long comicId,
                                      boolean hasCheckpoint) throws IOException {
        StorageRoot sourceRoot = StorageRootResolver.optional(storageProperties, sourceRootKey);
        Path comicDirectory = sourceRoot == null ? null : sourceRoot.resolve(String.valueOf(comicId));
        try {
            if (sourceRoot == null || !sourceRoot.exists() || comicDirectory == null
                    || !Files.isDirectory(comicDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(comicDirectory)
                    || !Files.getFileStore(exportRoot.getPath()).equals(Files.getFileStore(comicDirectory))) {
                throw new IOException("文件夹目录移动要求来源存储根与 EXPORT 位于同一卷 comicId=" + comicId);
            }
        } catch (IOException exception) {
            if (hasCheckpoint) {
                throw new ExportMoveOutException("目录移动恢复被阻止：来源与 EXPORT 卷状态已变化 comicId=" + comicId,
                        exception);
            }
            throw exception;
        }
    }

    private void writeDirectoryEntry(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private Path validatedRelativePath(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("\\")
                || relativePath.contains(":") || relativePath.startsWith("/")) {
            throw new IOException("目录移动检查点包含非法相对路径");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("目录移动检查点包含非法路径段");
            }
        }
        return Path.of(relativePath).normalize();
    }

    private Path sourcePath(String rootKey, String relativePath) {
        StorageRoot root = StorageRootResolver.optional(storageProperties, rootKey);
        if (root == null) {
            throw new IllegalStateException("文件夹导出来源存储根不可用: " + rootKey);
        }
        return root.resolve(relativePath);
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
