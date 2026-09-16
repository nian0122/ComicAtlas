package com.comicatlas.api.exporter.service;

/** 导出目录打开服务契约。 */
public interface ExportDirectoryService {
    ExportDirectoryOpenResult open(Long taskId);
}
