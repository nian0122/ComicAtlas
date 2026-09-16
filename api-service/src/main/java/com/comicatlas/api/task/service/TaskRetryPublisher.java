package com.comicatlas.api.task.service;

import com.comicatlas.api.exporter.service.ExportRetryService;
import com.comicatlas.api.importer.service.ImportRetryService;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.api.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 管理任务重试协调器，仅选择业务域策略并发布通用管理命令。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRetryPublisher {
    private static final int EVENT_ATTEMPT = 1;
    private static final String TRASH_MANIFEST_REF = "TRASH_MANIFEST";
    private static final Set<TaskType> COMMAND_OPERATIONS = Set.of(
            TaskType.LQ_GENERATE, TaskType.LQ_REGENERATE, TaskType.HQ_DELETE,
            TaskType.TRANSCODE, TaskType.METADATA_REFRESH, TaskType.COMIC_DELETE,
            TaskType.MEDIA_UPLOAD, TaskType.MEDIA_REPLACE, TaskType.MEDIA_TRASH,
            TaskType.CHAPTER_TRASH, TaskType.COMIC_RESTORE, TaskType.CHAPTER_RESTORE,
            TaskType.MEDIA_RESTORE, TaskType.COMIC_PURGE, TaskType.CHAPTER_PURGE,
            TaskType.MEDIA_PURGE);

    private final OutboxService outboxService;
    private final ExportRetryService exportRetryService;
    private final ImportRetryService importRetryService;

    public void publish(Long taskId, ManagementTaskItem item, int attempt) {
        publishManagementCommand(taskId, item, attempt);
        if (item.getOperationType() == TaskType.EXPORT) exportRetryService.retry(taskId, item, attempt);
        if (item.getOperationType() == TaskType.IMPORT) importRetryService.retry(taskId, item);
    }

    private void publishManagementCommand(Long taskId, ManagementTaskItem item, int attempt) {
        TaskType operation = item.getOperationType();
        if (operation == null || !COMMAND_OPERATIONS.contains(operation)) return;
        Long manifestTaskId = TRASH_MANIFEST_REF.equals(item.getResultRefType())
                ? item.getResultRefId() : null;
        ManagementCommandRequestedEvent event = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), EVENT_ATTEMPT, taskId, item.getId(), attempt,
                operation.name(), item.getTargetType(), item.getTargetId(), manifestTaskId);
        outboxService.enqueue(event, MqExchanges.MANAGEMENT, MqRoutingKeys.COMMAND_REQUESTED,
                taskId, item.getId(), attempt);
        log.info("重试已重新发布管理命令: taskId={}, itemId={}, attempt={}, operation={}",
                taskId, item.getId(), attempt, operation);
    }
}
