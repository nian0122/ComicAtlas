package com.comicatlas.api.exporter.service.impl;

import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
// 架构说明：Service 直接构造 LambdaUpdateWrapper 重置导出任务；条件更新应收口到 ExportTaskMapper。
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.constant.ExportFormats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 导出领域重试策略：恢复导出专表并重新发布导出任务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportRetryServiceImpl implements com.comicatlas.api.exporter.service.ExportRetryService {
    private final ExportTaskMapper exportTaskMapper;
    private final OutboxService outboxService;
    private final ComicMapper comicMapper;

    public void retry(Long taskId, ManagementTaskItem item, int attempt) {
        ExportTask exportTask = exportTaskMapper.selectByManagementTaskId(taskId);
        if (exportTask == null) {
            log.warn("导出专表不存在，跳过导出重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        if (ExportFormats.BATCH_DIRECTORY.equalsIgnoreCase(exportTask.getFormat())
                && !exportTask.getComicId().equals(item.getTargetId())) {
            // 批量目录任务的多个漫画项共享一个 Worker 任务，仅由首个漫画项发布整批重试事件。
            return;
        }
        if (ExportFormats.BATCH_DIRECTORY.equalsIgnoreCase(exportTask.getFormat())) {
            for (Long comicId : parseComicIds(exportTask)) {
                Comic comic = comicMapper.selectByIdForUpdate(comicId);
                if (comic != null && comic.getStatus() == ComicStatus.READY) {
                    comic.setStatus(ComicStatus.EXPORTING);
                    comicMapper.updateById(comic);
                }
            }
        }
        exportTaskMapper.resetForRetry(exportTask.getId(), ExportTaskStatus.PENDING);
        ExportTaskCreatedEvent event = new ExportTaskCreatedEvent(UUID.randomUUID(), Instant.now(),
                exportTask.getId(), exportTask.getComicId(),
                exportTask.getFormat() == null ? "ZIP" : exportTask.getFormat(), parseComicIds(exportTask));
        outboxService.enqueue(event, MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED,
                taskId, item.getId(), attempt);
    }

    private java.util.List<Long> parseComicIds(ExportTask exportTask) {
        if (exportTask.getComicIds() == null || exportTask.getComicIds().isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(exportTask.getComicIds().split(",")).map(Long::valueOf).toList();
    }
}
