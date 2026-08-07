package com.comicatlas.worker.export;

import com.comicatlas.worker.common.ComicTitleSanitizer;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.storage.ExportFileResolver;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageRoot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 导出编排：收集 → 构建清单 → 打包 ZIP。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportCollector exportCollector;
    private final ExportFileResolver exportFileResolver;
    private final ZipBuilder zipBuilder;
    private final MetadataJsonExporter metadataJsonExporter;
    private final StorageProperties storageProperties;

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
        long outputSize = zipBuilder.build(manifest, outputPath);
        return new ExportOutput(taskId, comicId, outputPath.getFileName().toString(), outputSize);
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
     * 章节目录名会做去重处理。
     */
    private ExportManifest buildManifest(ExportCollectResult result) {
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

        // 构建文件条目：按章节分组，去重目录名
        Set<String> usedPaths = new HashSet<>();
        for (ExportChapter chapter : result.chapters()) {
            String chapterDir = chapterTitles.getOrDefault(chapter.getId(), "chapter_" + chapter.getId());
            String uniqueDir = chapterDir;
            int counter = 1;
            while (usedPaths.contains(uniqueDir)) {
                uniqueDir = chapterDir + "(" + counter + ")";
                counter++;
            }
            usedPaths.add(uniqueDir);

            List<ExportMedia> chapterMedia = mediaByChapter.getOrDefault(chapter.getId(), List.of());
            List<ExportMedia> sortedMedia = chapterMedia.stream()
                    .sorted(Comparator.comparing(ExportMedia::getPageNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (ExportMedia media : sortedMedia) {
                try {
                    StorageRef ref = exportFileResolver.resolve(media);
                    Path sourceFile = exportFileResolver.resolveToPath(ref);
                    if (!Files.exists(sourceFile)) {
                        log.warn("导出跳过缺失文件: comicId={}, mediaId={}, path={}",
                                result.comic().getId(), media.getId(), sourceFile);
                        continue;
                    }
                    String fileName = Path.of(ref.relativePath()).getFileName().toString();
                    String targetPath = uniqueDir + "/" + fileName;
                    entries.add(new ExportManifest.Entry(targetPath, sourceFile));
                } catch (ExportFileNotFoundException e) {
                    log.warn("导出跳过无可用文件: comicId={}, mediaId={}", result.comic().getId(), media.getId());
                }
            }
        }
        return new ExportManifest(rootDirName, metadataJsonExporter.exportJson(result.comic().getId()), entries);
    }

    private String buildOutputFileName(Long comicId, String title) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeTitle = ComicTitleSanitizer.sanitize(title);
        return safeTitle + "_" + comicId + "_" + timestamp + ".zip";
    }
}
