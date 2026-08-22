package com.comicatlas.worker.exporter.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 导出清单 — ZIP 根目录名、metadata.json、ComicInfo.xml 内容、待打包文件条目。
 */
public record ExportManifest(String rootDirName, String metadataJson, String comicInfoXml, List<Entry> entries) {

    /** 兼容没有 ComicInfo.xml 的旧调用方与测试清单。 */
    public ExportManifest(String rootDirName, String metadataJson, List<Entry> entries) {
        this(rootDirName, metadataJson, null, entries);
    }

    /**
     * 待打包文件条目 — 规范化 targetPath（ZIP 内相对路径，仅正斜杠）、
     * 源文件绝对路径、预检时已知的源文件大小（字节）。
     */
    public record Entry(String targetPath, Path sourceFile, long sourceSize) {
    }
}
