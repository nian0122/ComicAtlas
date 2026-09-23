package com.comicatlas.worker.exporter.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 导出清单 — ZIP 根目录名、metadata.json、ComicInfo.xml 内容、待打包文件条目。
 */
public final class ExportManifest {
    private final String rootDirName;
    private final String metadataJson;
    private final String comicInfoXml;
    private final List<Entry> entries;

    public ExportManifest(String rootDirName, String metadataJson, String comicInfoXml, List<Entry> entries) {
        this.rootDirName = rootDirName;
        this.metadataJson = metadataJson;
        this.comicInfoXml = comicInfoXml;
        this.entries = entries;
    }

    public String rootDirName() { return rootDirName; }
    public String metadataJson() { return metadataJson; }
    public String comicInfoXml() { return comicInfoXml; }
    public List<Entry> entries() { return entries; }

    /** 兼容没有 ComicInfo.xml 的旧调用方与测试清单。 */
    public ExportManifest(String rootDirName, String metadataJson, List<Entry> entries) {
        this(rootDirName, metadataJson, null, entries);
    }

    /**
     * 待打包文件条目 — 规范化 targetPath（ZIP 内相对路径，仅正斜杠）、
     * 源文件绝对路径、预检时已知的源文件大小（字节）。
     */
    public static final class Entry {
        private final String targetPath;
        private final Path sourceFile;
        private final long sourceSize;

        public Entry(String targetPath, Path sourceFile, long sourceSize) {
            this.targetPath = targetPath;
            this.sourceFile = sourceFile;
            this.sourceSize = sourceSize;
        }

        public String targetPath() { return targetPath; }
        public Path sourceFile() { return sourceFile; }
        public long sourceSize() { return sourceSize; }
    }
}
