package com.comicatlas.worker.export;

import java.nio.file.Path;
import java.util.List;

/**
 * 导出清单 — ZIP 根目录名、metadata.json 内容、待打包文件条目。
 */
public record ExportManifest(String rootDirName, String metadataJson, List<Entry> entries) {

    /**
     * 待打包文件条目 — 规范化 targetPath（ZIP 内相对路径，仅正斜杠）、
     * 源文件绝对路径、预检时已知的源文件大小（字节）。
     */
    public record Entry(String targetPath, Path sourceFile, long sourceSize) {
    }
}
