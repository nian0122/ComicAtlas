package com.comicatlas.worker.exporter.service;

import java.io.IOException;

/** Worker 导出编排服务契约。 */
public interface ExportService {
    ExportOutput export(Long comicId, Long taskId) throws IOException;
    ExportOutput export(Long comicId, Long taskId, String format) throws IOException;
    String classifyExportError(Exception exception);

    record ExportOutput(Long taskId, Long comicId, String fileName, long size) { }
}
