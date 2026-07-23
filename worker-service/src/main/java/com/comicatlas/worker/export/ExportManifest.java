package com.comicatlas.worker.export;

import java.nio.file.Path;
import java.util.List;

/**
 * 导出清单 — ZIP 根目录名、metadata.json 内容、待打包文件条目。
 */
public record ExportManifest(String rootDirName, String metadataJson, List<Entry> entries) {

    public record Entry(String targetPath, Path sourceFile) {
    }
}
