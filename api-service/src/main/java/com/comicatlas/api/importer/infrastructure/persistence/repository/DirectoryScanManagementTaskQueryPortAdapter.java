package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.DirectoryScanManagementTaskQueryPort;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.application.service.TaskInternalQueryService;
import com.comicatlas.api.task.domain.model.TaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 目录扫描统一任务项查询端口的基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class DirectoryScanManagementTaskQueryPortAdapter implements DirectoryScanManagementTaskQueryPort {
    private final ManagementTaskService managementTaskService;

    @Override
    public ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType) {
        TaskInternalQueryService.ItemSnapshot item = managementTaskService.findActiveItem(targetType, targetId,
                operationType);
        return item == null ? null : new ItemSnapshot(item.id());
    }
}
