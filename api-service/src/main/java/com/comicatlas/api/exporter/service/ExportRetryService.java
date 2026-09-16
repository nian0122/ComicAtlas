package com.comicatlas.api.exporter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
// 架构说明：Service 直接构造 LambdaUpdateWrapper 重置导出任务；条件更新应收口到 ExportTaskMapper。
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
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
// TODO(LAYER-14): 具体 Service 实现位于 service 包，未与 Service 接口及 service/impl 实现分离。
@RequiredArgsConstructor
public class ExportRetryService {
    private final ExportTaskMapper exportTaskMapper;
    private final OutboxService outboxService;

    public void retry(Long taskId, ManagementTaskItem item, int attempt) {
        ExportTask exportTask = exportTaskMapper.selectOne(new LambdaQueryWrapper<ExportTask>()
                .eq(ExportTask::getManagementTaskId, taskId));
        if (exportTask == null) {
            log.warn("导出专表不存在，跳过导出重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        exportTaskMapper.resetForRetry(exportTask.getId(), ExportTaskStatus.PENDING);
        ExportTaskCreatedEvent event = new ExportTaskCreatedEvent(UUID.randomUUID(), Instant.now(),
                exportTask.getId(), exportTask.getComicId(),
                exportTask.getFormat() == null ? "ZIP" : exportTask.getFormat());
        outboxService.enqueue(event, MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED,
                taskId, item.getId(), attempt);
    }
}
