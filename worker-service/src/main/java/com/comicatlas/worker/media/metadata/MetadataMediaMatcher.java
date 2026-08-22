package com.comicatlas.worker.media.metadata;

import com.comicatlas.worker.persistence.record.MediaRecord;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** 负责 DB 媒体行与磁盘文件名的索引和布局兼容判断。 */
public class MetadataMediaMatcher {

    public HqIndex indexHqRows(Iterable<MediaRecord> rows, Long comicId) {
        return indexHqRows(rows, comicId, List.of());
    }

    public HqIndex indexHqRows(Iterable<MediaRecord> rows, Long comicId, List<String> warnings) {
        Map<String, MediaRecord> byBasename = new HashMap<>();
        Set<String> directoryKeys = new LinkedHashSet<>();
        for (MediaRecord row : rows) {
            if (MetadataScanSupport.isLqOnlyRow(row)) {
                continue;
            }
            String dirKey = MetadataScanSupport.extractDirKey(row.getHqPath(), comicId);
            if (dirKey == null) {
                warnings.add("忽略非法 hqPath: " + row.getHqPath());
                continue;
            }
            byBasename.putIfAbsent(MetadataScanSupport.basenameOf(row.getHqPath()), row);
            directoryKeys.add(dirKey);
        }
        return new HqIndex(byBasename, directoryKeys);
    }

    public LqIndex indexLqRows(Iterable<MediaRecord> rows, Long comicId) {
        return indexLqRows(rows, comicId, List.of());
    }

    public LqIndex indexLqRows(Iterable<MediaRecord> rows, Long comicId, List<String> warnings) {
        Map<String, MediaRecord> byBasename = new HashMap<>();
        Set<String> directoryKeys = new LinkedHashSet<>();
        for (MediaRecord row : rows) {
            String dirKey = MetadataScanSupport.extractDirKey(row.getLqPath(), comicId);
            if (dirKey == null) {
                warnings.add("忽略非法 lqPath: " + row.getLqPath());
                continue;
            }
            byBasename.putIfAbsent(MetadataScanSupport.basenameOf(row.getLqPath()), row);
            directoryKeys.add(dirKey);
        }
        return new LqIndex(byBasename, directoryKeys);
    }

    public String legacyDirectoryKey(Iterable<MediaRecord> rows, Long chapterId) {
        String chapterKey = String.valueOf(chapterId);
        for (MediaRecord row : rows) {
            String hqPath = row.getHqPath();
            if (hqPath == null) {
                continue;
            }
            String[] segments = hqPath.split("/");
            if (segments.length == 3 && !segments[1].equals(chapterKey)) {
                return segments[1];
            }
        }
        return null;
    }

    public record HqIndex(Map<String, MediaRecord> byBasename, Set<String> directoryKeys) {
    }

    public record LqIndex(Map<String, MediaRecord> byBasename, Set<String> directoryKeys) {
    }
}
