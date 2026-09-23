package com.comicatlas.api.exporter.model;

/** 导出目录打开结果，供 HTTP 层映射状态码。 */
public record ExportDirectoryOpenResult(Status status, String message) {
    public enum Status {
        OPENED,
        NOT_FOUND,
        NOT_SUPPORTED
    }
}
