package com.comicatlas.api.exporter.application.service.impl;

// 架构说明：Service 直接构造 LambdaUpdateWrapper 重置导出任务；条件更新应收口到 ExportTaskMapper。
import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.api.exporter.application.port.out.ExportPersistencePort;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 导出领域重试策略：恢复导出专表并重新发布导出任务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportRetryServiceImpl implements com.comicatlas.api.exporter.application.port.in.ExportRetryService {
    private final ExportPersistencePort persistencePort;
    private final OutboxService outboxService;

    public void retry(Long taskId, com.comicatlas.api.exporter.application.port.in.ExportRetryService.RetryItem item,
                      int attempt) {
        ExportPersistencePort.ExportTaskSnapshot exportTask = persistencePort.findTaskByManagementTaskId(taskId);
        if (exportTask == null) {
            log.warn("导出专表不存在，跳过导出重试入队: taskId={}, itemId={}", taskId, item.id());
            return;
        }
        persistencePort.resetTask(exportTask.id(), ExportTaskStatus.PENDING);
        ExportTaskCreatedEvent event = new ExportTaskCreatedEvent(UUID.randomUUID(), Instant.now(),
                exportTask.id(), exportTask.comicId(),
                exportTask.format() == null ? "ZIP" : exportTask.format());
        outboxService.enqueue(event, MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED,
                taskId, item.id(), attempt);
    }
}
