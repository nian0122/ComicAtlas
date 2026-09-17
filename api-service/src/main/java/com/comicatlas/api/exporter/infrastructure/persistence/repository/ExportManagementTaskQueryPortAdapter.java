package com.comicatlas.api.exporter.infrastructure.persistence.repository;

import com.comicatlas.api.exporter.application.port.out.ExportManagementTaskQueryPort;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 导出统一任务项查询端口的基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class ExportManagementTaskQueryPortAdapter implements ExportManagementTaskQueryPort {
    private final ManagementTaskService managementTaskService;

    @Override
    public ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType) {
        ManagementTaskItem item = managementTaskService.findActiveItem(targetType, targetId, operationType);
        return item == null ? null : new ItemSnapshot(item.getId());
    }
}
