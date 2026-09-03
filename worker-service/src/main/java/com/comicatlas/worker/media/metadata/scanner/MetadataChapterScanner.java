package com.comicatlas.worker.media.metadata.scanner;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.constant.MediaStatuses;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.media.metadata.MetadataScanSupport;
import com.comicatlas.worker.media.metadata.matcher.MetadataMediaMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 负责章节 HQ/LQ 目录定位和文件枚举，不参与媒体字段组装。 */
@Component
@RequiredArgsConstructor
public class MetadataChapterScanner {

    /** LQ 派生文件唯一扩展名。 */
    private static final String LQ_EXTENSION = ".webp";
    private static final String LQ_STATUS_NOT_GENERATED = MetadataScanSupport.LQ_STATUS_NOT_GENERATED;
    private static final String HQ_STATUS_DELETED = MetadataScanSupport.HQ_STATUS_DELETED;
    private static final String IMAGE_TYPE = MetadataScanSupport.IMAGE_TYPE;
    private static final String STATUS_READY = MediaStatuses.READY;

    private final StorageProperties storageProperties;

    public Path resolveHqDirectory(Long comicId, Long chapterId, String dirKey) {
        StorageRoot root = StorageRootResolver.required(storageProperties, StorageRootKeys.HQ);
        Path candidate = root.resolve(comicId + "/" + dirKey);
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }
        Path chapterDirectory = root.resolve(comicId + "/" + chapterId);
        return Files.isDirectory(chapterDirectory, LinkOption.NOFOLLOW_LINKS) ? chapterDirectory : null;
    }

    public Path resolveLqDirectory(Long comicId, Long chapterId, String dirKey) {
        StorageRoot root = StorageRootResolver.optional(storageProperties, StorageRootKeys.LQ);
        if (root == null) {
            return null;
        }
        Path candidate = root.resolve(comicId + "/" + dirKey);
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }
        Path chapterDirectory = root.resolve(comicId + "/" + chapterId);
        return Files.isDirectory(chapterDirectory, LinkOption.NOFOLLOW_LINKS) ? chapterDirectory : null;
    }

    public List<Path> list(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.collect(Collectors.toList());
        }
    }

    /** 扫描 HQ 缺失时的仅 LQ 章节，返回可直接组装快照的媒体条目。 */
    public ScanResult scanLqOnlyChapter(Long comicId, Long chapterId, List<MediaRecord> rows,
                                        List<String> warnings, MetadataMediaMatcher matcher) {
        MetadataMediaMatcher.LqIndex index = matcher.indexLqRows(rows, comicId, warnings);
        Map<String, MediaRecord> rowsByBasename = index.byBasename();
        if (rowsByBasename.isEmpty()) {
            return new ScanResult(List.of(), warnings, null);
        }
        String directoryKey = index.directoryKeys().size() == 1
                ? index.directoryKeys().iterator().next() : String.valueOf(chapterId);
        Path directory = resolveLqDirectory(comicId, chapterId, directoryKey);
        if (directory == null) {
            throw new IllegalStateException("仅 LQ 章节目录不存在，拒绝应用不完整扫描: "
                    + comicId + "/" + chapterId);
        }
        List<Path> files;
        try {
            files = list(directory);
        } catch (IOException e) {
            throw new IllegalStateException("读取仅 LQ 章节目录失败，拒绝应用不完整扫描: "
                    + comicId + "/" + chapterId, e);
        }
        files.sort(com.comicatlas.worker.importer.parser.NaturalPathComparator.INSTANCE);
        List<MediaSnapshot> mediaItems = new java.util.ArrayList<>(rowsByBasename.size());
        Set<Long> matchedIds = new HashSet<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.startsWith(".") || isHidden(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String lowerFileName = fileName.toLowerCase();
            if (!lowerFileName.endsWith(LQ_EXTENSION)) {
                warnings.add("忽略非 LQ 产物: " + fileName);
                continue;
            }
            MediaRecord row = rowsByBasename.get(fileName);
            if (row == null) {
                warnings.add("LQ 文件无对应 DB 记录: " + fileName);
                continue;
            }
            matchedIds.add(row.getId());
            mediaItems.add(new MediaSnapshot(row.getId(), MetadataScanSupport.versionOrZero(row.getVersion()),
                    comicId + "/" + chapterId + "/" + fileName, HQ_STATUS_DELETED,
                    row.getStatus() != null ? row.getStatus() : STATUS_READY,
                    row.getPageNumber() != null ? row.getPageNumber() : 0, 0L, IMAGE_TYPE,
                    null, null, null, null, null, null, STATUS_READY,
                    requiredSize(file, comicId, chapterId),
                    comicId + "/" + chapterId + "/" + fileName));
        }
        for (MediaRecord row : rows) {
            if (row.getLqPath() == null || row.getLqPath().isBlank() || matchedIds.contains(row.getId())) {
                continue;
            }
            String fileName = MetadataScanSupport.basenameOf(row.getLqPath());
            if (fileName.isEmpty() || !rowsByBasename.containsKey(fileName)) {
                continue;
            }
            mediaItems.add(new MediaSnapshot(row.getId(), MetadataScanSupport.versionOrZero(row.getVersion()),
                    comicId + "/" + chapterId + "/" + fileName, HQ_STATUS_DELETED,
                    row.getStatus() != null ? row.getStatus() : STATUS_READY,
                    row.getPageNumber() != null ? row.getPageNumber() : 0, 0L, IMAGE_TYPE,
                    null, null, null, null, null, null, LQ_STATUS_NOT_GENERATED, 0L, null));
        }
        return new ScanResult(mediaItems, warnings, null);
    }

    private static boolean isHidden(Path file) {
        try {
            return Files.isHidden(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static long requiredSize(Path file, Long comicId, Long chapterId) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取 LQ 文件大小失败，拒绝应用不完整扫描: "
                    + comicId + "/" + chapterId + "/" + file.getFileName(), e);
        }
    }

    public record ScanResult(List<MediaSnapshot> mediaItems, List<String> warnings, String legacyDirKey) {
    }
}
