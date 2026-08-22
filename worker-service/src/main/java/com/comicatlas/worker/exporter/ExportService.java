package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.publisher.ExportArchivePublisher;
import com.comicatlas.worker.exporter.exception.ExportFileNotFoundException;
import com.comicatlas.worker.exporter.exception.ExportManifestBuildException;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.worker.shared.common.ComicTitleSanitizer;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 导出编排：收集 → 构建清单 → 打包 ZIP → 原子发布任务目录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

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
    /** 无标题章节的目录名兜底前缀。 */
    private static final String CHAPTER_DIR_PREFIX = "chapter_";
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

    public record ExportOutput(Long taskId, Long comicId, String fileName, long size) {
    }

    public ExportOutput export(Long comicId, Long taskId) throws IOException {
        return export(comicId, taskId, ExportFormats.ZIP);
    }

    public ExportOutput export(Long comicId, Long taskId, String format) throws IOException {
        ExportCollectResult result = exportCollector.collect(comicId);
        ExportManifest manifest = buildManifest(result);

        StorageRoot exportRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.EXPORT);
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
        }

        String baseFileName = buildOutputFileName(comicId, result.comic().getTitle(), format);
        Path stagingDir = exportRoot.resolve(STAGING_DIR_PREFIX + taskId);
        Path finalDir = exportRoot.resolve(String.valueOf(taskId));
        deleteRecursively(stagingDir);
        // 打包到 staging（发布由 archivePublisher 原子执行）
        zipBuilder.build(manifest, stagingDir.resolve(baseFileName));
        ExportArchivePublisher.PublishResult publishResult = archivePublisher.publish(
                taskId, stagingDir, finalDir, manifest);
        return new ExportOutput(taskId, comicId, publishResult.fileName(), publishResult.size());
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
     * <p>章节目录名会做去重处理。异常消息只携带 comicId/mediaId 与相对 targetPath，
     * 不输出宿主机绝对路径。
     */
    private ExportManifest buildManifest(ExportCollectResult result) {
        Long comicId = result.comic().getId();
        String rootDirName = ComicTitleSanitizer.sanitize(result.comic().getTitle());

        List<ExportManifest.Entry> entries = new ArrayList<>();
        Map<Long, List<MediaRecord>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(MediaRecord::getChapterId));

        // 构建章节标题映射
        Map<Long, String> chapterTitles = result.chapters().stream()
                .collect(Collectors.toMap(ChapterRecord::getId, chapter ->
                        chapter.getTitle() != null && !chapter.getTitle().isBlank()
                                ? ComicTitleSanitizer.sanitize(chapter.getTitle())
                                : CHAPTER_DIR_PREFIX + chapter.getId()));

        // 构建文件条目：按章节分组，去重目录名，并做目标路径冲突与容量预检
        Set<String> usedChapterDirs = new HashSet<>();
        Set<String> usedTargetPaths = new HashSet<>();
        long mediaTotalSize = 0L;
        for (ChapterRecord chapter : result.chapters()) {
            String chapterDir = chapterTitles.getOrDefault(chapter.getId(), CHAPTER_DIR_PREFIX + chapter.getId());
            String uniqueDir = chapterDir;
            int counter = 1;
            while (usedChapterDirs.contains(uniqueDir)) {
                uniqueDir = chapterDir + "(" + counter + ")";
                counter++;
            }
            usedChapterDirs.add(uniqueDir);

            List<MediaRecord> chapterMedia = mediaByChapter.getOrDefault(chapter.getId(), List.of());
            List<MediaRecord> sortedMedia = chapterMedia.stream()
                    .sorted(Comparator.comparing(MediaRecord::getPageNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (MediaRecord media : sortedMedia) {
                ExportManifest.Entry entry = buildEntry(comicId, media, uniqueDir, usedTargetPaths);
                entries.add(entry);
                mediaTotalSize = addSizes(comicId, media.getId(), mediaTotalSize, entry.sourceSize());
            }
        }
        if (entries.size() != result.allMedia().size()) {
            throw new ExportManifestBuildException(
                    "导出清单构建失败：comicId=" + comicId + ", 媒体条目数与采集数不一致: entries="
                            + entries.size() + ", allMedia=" + result.allMedia().size());
        }

        String metadataJson = metadataJsonExporter.exportJson(result.comic().getId());
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

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("清理 staging 目录失败: {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.warn("清理 staging 目录失败: {}", dir, ex);
        }
    }

    private String buildOutputFileName(Long comicId, String title, String format) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeTitle = ComicTitleSanitizer.sanitize(title);
        String extension = ExportFormats.CBZ.equalsIgnoreCase(format) ? CBZ_EXTENSION : ZIP_EXTENSION;
        return safeTitle + "_" + comicId + "_" + timestamp + extension;
    }
}
