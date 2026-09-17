package com.comicatlas.api.exporter.application.port.in;

import com.comicatlas.api.exporter.domain.model.ExportDirectoryOpenResult;

/** 导出目录打开服务契约。 */
public interface ExportDirectoryService {
    ExportDirectoryOpenResult open(Long taskId);
}
