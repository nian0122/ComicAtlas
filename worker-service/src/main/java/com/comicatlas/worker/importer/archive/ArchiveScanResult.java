package com.comicatlas.worker.importer.archive;

import java.util.List;

/** 压缩包扫描结果，可供导入预检、错误提示和后续预览复用。 */
public record ArchiveScanResult(
        ArchiveFormat format,
        List<PathEntry> images,
        List<PathEntry> videos,
        List<PathEntry> unsupportedFiles,
        List<String> directories,
        List<String> emptyDirectories,
        List<String> duplicateFileNames,
        List<String> damagedFiles,
        List<String> missingVolumes,
        boolean integrityPassed
) {
    public record PathEntry(String name, long size) {}
}
