package com.comicatlas.api.task.application.port.out;

import com.comicatlas.api.exporter.application.port.in.ExportRetryService;
import com.comicatlas.api.importer.application.port.in.ImportRetryService;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
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

    public void publish(Long taskId, RetryItem item, int attempt) {
        publishManagementCommand(taskId, item, attempt);
        if (item.operationType() == TaskType.EXPORT) {
            exportRetryService.retry(taskId,
                    new com.comicatlas.api.exporter.application.port.in.ExportRetryService.RetryItem(
                            item.id(), item.resultRefType(), item.resultRefId()), attempt);
        }
        if (item.operationType() == TaskType.IMPORT) {
            importRetryService.retry(taskId, item.id());
        }
    }

    /** 管理任务重试所需的最小应用快照。 */
    public record RetryItem(Long id, TaskType operationType, String resultRefType, Long resultRefId,
                            String targetType, Long targetId) { }

    private void publishManagementCommand(Long taskId, RetryItem item, int attempt) {
        TaskType operation = item.operationType();
        if (operation == null || !COMMAND_OPERATIONS.contains(operation)) {
            return;
        }
        Long manifestTaskId = TRASH_MANIFEST_REF.equals(item.resultRefType())
                ? item.resultRefId() : null;
        ManagementCommandRequestedEvent event = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), EVENT_ATTEMPT, taskId, item.id(), attempt,
                operation.name(), item.targetType(), item.targetId(), manifestTaskId);
        outboxService.enqueue(event, MqExchanges.MANAGEMENT, MqRoutingKeys.COMMAND_REQUESTED,
                taskId, item.id(), attempt);
        log.info("重试已重新发布管理命令: taskId={}, itemId={}, attempt={}, operation={}",
                taskId, item.id(), attempt, operation);
    }
}
