package com.comicatlas.worker.export;

import com.comicatlas.worker.common.ComicTitleSanitizer;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.storage.ExportFileResolver;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import lombok.RequiredArgsConstructor;
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

/** 导出编排：收集 → 构建清单 → 打包 ZIP。 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportCollector exportCollector;
    private final ExportFileResolver exportFileResolver;
    private final ZipBuilder zipBuilder;
    private final MetadataJsonExporter metadataJsonExporter;
    private final StorageProperties storageProperties;
    private final WorkerConfig workerConfig;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record ExportOutput(Long taskId, Long comicId, String fileName, long size) {}

    public ExportOutput export(Long comicId, Long taskId) throws IOException {
        ExportCollectResult result = exportCollector.collect(comicId);
        ExportManifest manifest = buildManifest(result);

        StorageRoot exportRoot = storageProperties.getRoots().get("EXPORT");
        if (exportRoot == null || !exportRoot.exists()) {
            throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
        }

        String outputFileName = buildOutputFileName(comicId, result.comic().getTitle());
        Path outputPath = exportRoot.resolve(outputFileName);
        ZipBuilder.ZipBuildResult zipResult = zipBuilder.build(manifest, outputPath);
        return new ExportOutput(taskId, comicId, outputPath.getFileName().toString(), zipResult.totalSize());
    }

    /** 供 handler 发失败事件使用。 */
    public String classifyExportError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("ZIP") || msg.contains("zip")) { return "ZIP_ERROR"; }
        if (msg.contains("collect") || msg.contains("Collect")) { return "COLLECT_ERROR"; }
        if (msg.contains("manifest") || msg.contains("Manifest")) { return "MANIFEST_ERROR"; }
        if (msg.contains("STORAGE") || msg.contains("storage") || msg.contains("EXPORT")) { return "STORAGE_ERROR"; }
        return "EXPORT_ERROR";
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
        Map<Long, List<ExportMedia>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(ExportMedia::getChapterId));

        // 构建章节标题映射
        Map<Long, String> chapterTitles = result.chapters().stream()
                .collect(Collectors.toMap(ExportChapter::getId, chapter ->
                        chapter.getTitle() != null && !chapter.getTitle().isBlank()
                                ? ComicTitleSanitizer.sanitize(chapter.getTitle())
                                : "chapter_" + chapter.getId()));

        // 构建文件条目：按章节分组，去重目录名，并做目标路径冲突与容量预检
        Set<String> usedChapterDirs = new HashSet<>();
        Set<String> usedTargetPaths = new HashSet<>();
        long mediaTotalSize = 0L;
        for (ExportChapter chapter : result.chapters()) {
            String chapterDir = chapterTitles.getOrDefault(chapter.getId(), "chapter_" + chapter.getId());
            String uniqueDir = chapterDir;
            int counter = 1;
            while (usedChapterDirs.contains(uniqueDir)) {
                uniqueDir = chapterDir + "(" + counter + ")";
                counter++;
            }
            usedChapterDirs.add(uniqueDir);

            List<ExportMedia> chapterMedia = mediaByChapter.getOrDefault(chapter.getId(), List.of());
            List<ExportMedia> sortedMedia = chapterMedia.stream()
                    .sorted(Comparator.comparing(ExportMedia::getPageNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (ExportMedia media : sortedMedia) {
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
        return new ExportManifest(rootDirName, metadataJson, entries);
    }

    /**
     * 构建单个媒体条目 — 解析源文件并做可用性预检。
     *
     * @throws ExportManifestBuildException 无可用文件、文件不可读/非普通文件、大小超限或目标路径冲突
     */
    private ExportManifest.Entry buildEntry(Long comicId, ExportMedia media, String uniqueDir,
                                            Set<String> usedTargetPaths) {
        Long mediaId = media.getId();
        StorageRef ref;
        try {
            ref = exportFileResolver.resolve(media);
        } catch (ExportFileNotFoundException e) {
            throw manifestBuildError(comicId, mediaId, "无可用媒体文件（HQ 缺失且 LQ 未就绪）", e);
        }

        Path sourceFile = exportFileResolver.resolveToPath(ref);
        if (!Files.isRegularFile(sourceFile)) {
            throw manifestBuildError(comicId, mediaId, "源文件缺失或非普通文件: " + ref.relativePath());
        }
        if (!Files.isReadable(sourceFile)) {
            throw manifestBuildError(comicId, mediaId, "源文件不可读: " + ref.relativePath());
        }

        long sourceSize;
        try {
            sourceSize = Files.size(sourceFile);
        } catch (IOException e) {
            throw manifestBuildError(comicId, mediaId, "读取源文件大小失败: " + ref.relativePath(), e);
        }
        if (sourceSize > maxEntrySize()) {
            throw manifestBuildError(comicId, mediaId, "单文件超限: " + sourceSize
                    + " 字节 > maxEntrySize=" + maxEntrySize());
        }

        String fileName = Path.of(ref.relativePath()).getFileName().toString();
        String targetPath = normalizeTargetPath(uniqueDir + "/" + fileName);
        String folded = targetPath.toLowerCase(Locale.ROOT);
        if (!usedTargetPaths.add(folded)) {
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
        } catch (ArithmeticException e) {
            throw new ExportManifestBuildException(
                    "导出清单构建失败：comicId=" + comicId + ", mediaId=" + mediaId + ", 媒体总量累加溢出", e);
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

    private String buildOutputFileName(Long comicId, String title) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeTitle = ComicTitleSanitizer.sanitize(title);
        return safeTitle + "_" + comicId + "_" + timestamp + ".zip";
    }
}
