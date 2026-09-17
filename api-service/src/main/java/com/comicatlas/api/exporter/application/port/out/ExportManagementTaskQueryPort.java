package com.comicatlas.api.exporter.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;

/** 导出结果应用服务查询统一任务项的输出端口。 */
public interface ExportManagementTaskQueryPort {
    ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType);

    record ItemSnapshot(Long id) { }
}
