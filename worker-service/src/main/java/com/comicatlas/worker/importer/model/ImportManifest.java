package com.comicatlas.worker.importer.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 导入清单（恢复点）。
 * files[].source 为相对 sourceRoot 的相对路径；files[].target 为 HQ 相对路径（comicId/chapterGlobalOrder/fileName）。
 * metadata 为完整 v3 metadata（含 MediaAnalyzer 提取的文件元信息），恢复时零依赖源文件。
 */
public record ImportManifest(
    int version,
    long taskId,
    String sourceType,
    String sourceRoot,
    JsonNode metadata,
    List<ImportFile> files
) {
    public record ImportFile(String source, String target, long size) {}
}
