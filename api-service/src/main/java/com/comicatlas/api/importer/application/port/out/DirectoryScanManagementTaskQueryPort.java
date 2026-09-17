package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;

/** 目录扫描应用服务查询统一任务项的输出端口。 */
public interface DirectoryScanManagementTaskQueryPort {
    ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType);

    record ItemSnapshot(Long id) { }
}
