package com.comicatlas.worker.exporter.service;

import java.io.IOException;

/** Worker 导出编排服务契约。 */
public interface ExportService {
    ExportOutput export(Long comicId, Long taskId) throws IOException;
    ExportOutput export(Long comicId, Long taskId, String format) throws IOException;
    String classifyExportError(Exception exception);

    static final class ExportOutput {
        private final Long taskId;
        private final Long comicId;
        private final String fileName;
        private final long size;

        public ExportOutput(Long taskId, Long comicId, String fileName, long size) {
            this.taskId = taskId;
            this.comicId = comicId;
            this.fileName = fileName;
            this.size = size;
        }

        public Long taskId() { return taskId; }
        public Long comicId() { return comicId; }
        public String fileName() { return fileName; }
        public long size() { return size; }
    }
}
