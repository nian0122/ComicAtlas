package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportManagementTaskQueryPort;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.application.service.TaskInternalQueryService;
import com.comicatlas.api.task.domain.model.TaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 导入管理任务查询端口的基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class ImportManagementTaskQueryPortAdapter implements ImportManagementTaskQueryPort {
    private final ManagementTaskService managementTaskService;

    @Override
    public TaskSnapshot findByIdempotencyKey(String idempotencyKey) {
        TaskInternalQueryService.TaskSnapshot task = managementTaskService.findByIdempotencyKey(idempotencyKey);
        return task == null ? null : new TaskSnapshot(task.id(), task.idempotencyPayloadHash());
    }

    @Override
    public ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType) {
        TaskInternalQueryService.ItemSnapshot item = managementTaskService.findActiveItem(targetType, targetId,
                operationType);
        return item == null ? null : new ItemSnapshot(item.id());
    }
}
