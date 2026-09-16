package com.comicatlas.api.exporter.service;

import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;

/** 导出任务重试服务契约。 */
public interface ExportRetryService {
    void retry(Long taskId, ManagementTaskItem item, int attempt);
}
