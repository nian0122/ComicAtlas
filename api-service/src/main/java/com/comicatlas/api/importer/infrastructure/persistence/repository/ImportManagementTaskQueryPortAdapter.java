package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportManagementTaskQueryPort;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 导入管理任务查询端口的基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class ImportManagementTaskQueryPortAdapter implements ImportManagementTaskQueryPort {
    private final ManagementTaskService managementTaskService;

    @Override
    public TaskSnapshot findByIdempotencyKey(String idempotencyKey) {
        ManagementTask task = managementTaskService.findByIdempotencyKey(idempotencyKey);
        return task == null ? null : new TaskSnapshot(task.getId(), task.getIdempotencyPayloadHash());
    }

    @Override
    public ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType) {
        ManagementTaskItem item = managementTaskService.findActiveItem(targetType, targetId, operationType);
        return item == null ? null : new ItemSnapshot(item.getId());
    }
}
