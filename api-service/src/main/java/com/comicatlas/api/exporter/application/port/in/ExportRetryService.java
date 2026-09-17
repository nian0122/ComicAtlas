package com.comicatlas.api.exporter.application.port.in;

/** 导出任务重试服务契约。 */
public interface ExportRetryService {
    void retry(Long taskId, RetryItem item, int attempt);

    record RetryItem(Long id, String resultRefType, Long resultRefId) { }
}
