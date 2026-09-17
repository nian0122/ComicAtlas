package com.comicatlas.api.importer.application.port.in;

/** 导入任务重试服务契约。 */
public interface ImportRetryService {
    void retry(Long taskId, Long itemId);
}
