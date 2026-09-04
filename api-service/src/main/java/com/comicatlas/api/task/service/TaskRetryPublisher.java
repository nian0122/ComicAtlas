package com.comicatlas.api.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.exporter.entity.ExportTask;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.exporter.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 管理任务重试消息发布器。
 *
 * <p>仅负责根据任务项类型恢复对应的下游消息，不修改管理任务状态；状态变更由
 * {@link ManagementTaskService} 在同一事务中完成。</p>
 */
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
    private final ExportTaskMapper exportTaskMapper;
    private final ImportTaskMapper importTaskMapper;
    private final ImportRetryCoordinator importRetryCoordinator;

    /**
     * 按任务项类型重新发布消息。
     *
     * @param taskId 管理任务 ID
     * @param item 任务项
     * @param attempt 新的任务尝试次数
     */
    public void publish(Long taskId, ManagementTaskItem item, int attempt) {
        publishManagementCommand(taskId, item, attempt);
        publishExportCommand(taskId, item, attempt);
        publishImportCommand(taskId, item, attempt);
    }

    private void publishManagementCommand(Long taskId, ManagementTaskItem item, int attempt) {
        TaskType operation = item.getOperationType();
        if (operation == null || !COMMAND_OPERATIONS.contains(operation)) {
            return;
        }
        Long manifestTaskId = TRASH_MANIFEST_REF.equals(item.getResultRefType())
                ? item.getResultRefId() : null;
        ManagementCommandRequestedEvent event = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), EVENT_ATTEMPT,
                taskId, item.getId(), attempt, operation.name(), item.getTargetType(),
                item.getTargetId(), manifestTaskId);
        outboxService.enqueue(event, MqExchanges.MANAGEMENT, MqRoutingKeys.COMMAND_REQUESTED,
                taskId, item.getId(), attempt);
        log.info("重试已重新发布管理命令: taskId={}, itemId={}, attempt={}, operation={}",
                taskId, item.getId(), attempt, operation);
    }

    private void publishExportCommand(Long taskId, ManagementTaskItem item, int attempt) {
        if (item.getOperationType() != TaskType.EXPORT) {
            return;
        }
        ExportTask exportTask = exportTaskMapper.selectOne(new LambdaQueryWrapper<ExportTask>()
                .eq(ExportTask::getManagementTaskId, taskId));
        if (exportTask == null) {
            log.warn("导出专表不存在，跳过导出重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                .eq(ExportTask::getId, exportTask.getId())
                .set(ExportTask::getStatus, ExportTaskStatus.PENDING)
                .set(ExportTask::getProgress, 0)
                .set(ExportTask::getErrorMsg, null)
                .set(ExportTask::getCompletedAt, null));
        ExportTaskCreatedEvent event = new ExportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), exportTask.getId(), exportTask.getComicId(),
                exportTask.getFormat() == null ? "ZIP" : exportTask.getFormat());
        outboxService.enqueue(event, MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED,
                taskId, item.getId(), attempt);
        log.info("导出任务重试已重新入队: taskId={}, itemId={}, attempt={}, exportTaskId={}",
                taskId, item.getId(), attempt, exportTask.getId());
    }

    private void publishImportCommand(Long taskId, ManagementTaskItem item, int attempt) {
        if (item.getOperationType() != TaskType.IMPORT) {
            return;
        }
        ImportTask importTask = importTaskMapper.selectOne(new LambdaQueryWrapper<ImportTask>()
                .eq(ImportTask::getManagementTaskId, taskId));
        if (importTask == null) {
            log.warn("导入任务不存在，跳过导入重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        boolean retried = importRetryCoordinator.retry(importTask);
        if (!retried && importTask.getStatus() != ImportTaskStatus.PENDING) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "导入任务非终态且未被重置，无法重试入队: taskId=" + taskId
                            + ", importTaskId=" + importTask.getId()
                            + ", status=" + importTask.getStatus());
        }
        log.info("导入任务重试已重新入队: taskId={}, itemId={}, attempt={}, importTaskId={}, retried={}",
                taskId, item.getId(), attempt, importTask.getId(), retried);
    }
}
