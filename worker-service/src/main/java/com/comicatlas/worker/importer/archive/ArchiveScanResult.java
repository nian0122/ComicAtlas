package com.comicatlas.worker.importer.archive;

import java.util.List;

/** 压缩包扫描结果，可供导入预检、错误提示和后续预览复用。 */
public final class ArchiveScanResult {
    private final ArchiveFormat format;
    private final List<PathEntry> images;
    private final List<PathEntry> videos;
    private final List<PathEntry> unsupportedFiles;
    private final List<String> directories;
    private final List<String> emptyDirectories;
    private final List<String> duplicateFileNames;
    private final List<String> damagedFiles;
    private final List<String> missingVolumes;
    private final boolean integrityPassed;

    public ArchiveScanResult(ArchiveFormat format, List<PathEntry> images, List<PathEntry> videos,
                             List<PathEntry> unsupportedFiles, List<String> directories,
                             List<String> emptyDirectories, List<String> duplicateFileNames,
                             List<String> damagedFiles, List<String> missingVolumes,
                             boolean integrityPassed) {
        this.format = format;
        this.images = images;
        this.videos = videos;
        this.unsupportedFiles = unsupportedFiles;
        this.directories = directories;
        this.emptyDirectories = emptyDirectories;
        this.duplicateFileNames = duplicateFileNames;
        this.damagedFiles = damagedFiles;
        this.missingVolumes = missingVolumes;
        this.integrityPassed = integrityPassed;
    }

    public ArchiveFormat format() { return format; }
    public List<PathEntry> images() { return images; }
    public List<PathEntry> videos() { return videos; }
    public List<PathEntry> unsupportedFiles() { return unsupportedFiles; }
    public List<String> directories() { return directories; }
    public List<String> emptyDirectories() { return emptyDirectories; }
    public List<String> duplicateFileNames() { return duplicateFileNames; }
    public List<String> damagedFiles() { return damagedFiles; }
    public List<String> missingVolumes() { return missingVolumes; }
    public boolean integrityPassed() { return integrityPassed; }

    public static final class PathEntry {
        private final String name;
        private final long size;

        public PathEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }

        public String name() { return name; }
        public long size() { return size; }
    }
}
