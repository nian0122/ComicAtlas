package com.comicatlas.api.exporter.application.port.in;

import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;

/** 导出任务重试服务契约。 */
public interface ExportRetryService {
    void retry(Long taskId, ManagementTaskItem item, int attempt);
}
