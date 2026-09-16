package com.comicatlas.api.importer.service;

import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;

/** 导入任务重试服务契约。 */
public interface ImportRetryService {
    void retry(Long taskId, ManagementTaskItem item);
}
