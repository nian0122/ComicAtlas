package com.comicatlas.api.recovery.application.service.impl;

import com.comicatlas.api.recovery.interfaces.rest.dto.RecoveryProgressVO;
import com.comicatlas.api.recovery.engine.RecoveryEngine;
import com.comicatlas.api.recovery.domain.model.RecoveryTaskStatus;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.RecoveryTaskSnapshot;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.recovery.application.port.in.RecoveryBatchService;
import com.comicatlas.common.event.RecoveryFailedEvent;
import com.comicatlas.common.event.RecoveryScanCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.dao.DataAccessException;
import com.comicatlas.contract.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;

/** 恢复批次业务编排：执行逐本恢复、累计进度并维护恢复任务生命周期。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryBatchServiceImpl implements RecoveryBatchService {
    private static final String EVENT_KEY_PREFIX = "mq:event:";
    private static final String TARGET_TYPE_SYSTEM = "SYSTEM";
    private static final String RESULT_REF_TYPE = "RECOVERY_TASK";
    private static final String STAGE_RECOVERY = "RECOVERY";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    private static final EnumSet<RecoveryTaskStatus> TERMINAL_STATUSES = EnumSet.of(
            RecoveryTaskStatus.SUCCEEDED, RecoveryTaskStatus.FAILED, RecoveryTaskStatus.CANCELLED);

    private final RecoveryEngine recoveryEngine;
    private final RecoveryTaskPersistencePort persistencePort;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ManagementTaskService managementTaskService;

    public void processScanCompleted(RecoveryScanCompletedEvent event) {
        String key = EVENT_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            return;
        }
        RecoveryTaskSnapshot task = persistencePort.findById(event.taskId());
        if (task == null || TERMINAL_STATUSES.contains(task.status())) {
            markProcessed(key);
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        task = save(task, RecoveryTaskStatus.RUNNING, event.comicIds().size(), 0, 0, 0, 0,
                null, null, startedAt, task.endedAt());
        ManagementTaskItemResponse item = syncItem(event.taskId(), ManagementTaskStatus.RUNNING, null, null, null);
        int processed = 0, recovered = 0, skipped = 0, placeholder = 0, errors = 0;
        for (Long comicId : event.comicIds()) {
            try {
                RecoveryProgressVO progress = recoveryEngine.processComicDir(comicId, processed);
                processed = progress.totalComics(); recovered += progress.recoveredComics();
                skipped += progress.skippedComics(); placeholder += progress.placeholderComics();
                errors += progress.errorComics();
                String errorMessage = progress.lastError() != null ? progress.lastError() : task.errorMessage();
                task = save(task, RecoveryTaskStatus.RUNNING, event.comicIds().size(), recovered, skipped,
                        placeholder, errors, errorMessage, task.errorDetails(), task.startedAt(), task.endedAt());
                updateProgress(item, processed, event.comicIds().size(), event.taskId(), recovered, skipped, placeholder, errors);
            } catch (BusinessException exception) {
                log.error("恢复漫画失败: taskId={}, comicId={}", event.taskId(), comicId, exception);
                errors++; processed++;
                task = save(task, RecoveryTaskStatus.RUNNING, event.comicIds().size(), recovered, skipped,
                        placeholder, errors, exception.getMessage(), task.errorDetails(), task.startedAt(), task.endedAt());
                updateProgress(item, processed, event.comicIds().size(), event.taskId(), recovered, skipped, placeholder, errors);
            }
        }
        task = save(task, RecoveryTaskStatus.SUCCEEDED, task.totalComics(), recovered, skipped, placeholder,
                errors, task.errorMessage(), task.errorDetails(), task.startedAt(), LocalDateTime.now());
        syncItem(event.taskId(), ManagementTaskStatus.SUCCEEDED, null, RESULT_REF_TYPE, event.taskId());
        markProcessed(key);
    }

    private void updateProgress(ManagementTaskItemResponse item, int processed, int total, Long taskId,
            int recovered, int skipped, int placeholder, int errors) {
        if (item != null && total > 0) {
            int percent = Math.min(100, (recovered + skipped + placeholder + errors) * 100 / total);
            managementTaskService.updateItemProgress(item.getId(), 0, percent, STAGE_RECOVERY);
        }
    }

    public void processFailed(RecoveryFailedEvent event) {
        String key = EVENT_KEY_PREFIX + event.eventId();
        if (isProcessed(key)) {
            return;
        }
        RecoveryTaskSnapshot task = persistencePort.findById(event.taskId());
        if (task == null || TERMINAL_STATUSES.contains(task.status())) { markProcessed(key); return; }
        save(task, RecoveryTaskStatus.FAILED, task.totalComics(), task.recoveredComics(), task.skippedComics(),
                task.placeholderComics(), task.errorComics(), event.errorMessage(), task.errorDetails(),
                task.startedAt(), LocalDateTime.now());
        syncItem(event.taskId(), ManagementTaskStatus.FAILED, event.errorMessage(), RESULT_REF_TYPE, event.taskId());
        markProcessed(key);
    }

    public void markProcessingFailure(Long taskId, Exception exception) {
        try {
            RecoveryTaskSnapshot task = persistencePort.findById(taskId);
            if (task != null && !TERMINAL_STATUSES.contains(task.status())) {
                String errorMessage = "事件处理异常: " + exception.getMessage();
                save(task, RecoveryTaskStatus.FAILED, task.totalComics(), task.recoveredComics(),
                        task.skippedComics(), task.placeholderComics(), task.errorComics(), errorMessage,
                        task.errorDetails(), task.startedAt(), LocalDateTime.now());
                syncItem(taskId, ManagementTaskStatus.FAILED, errorMessage, RESULT_REF_TYPE, taskId);
            }
        } catch (DataAccessException updateException) {
            log.error("标记恢复任务失败时出错, taskId={}", taskId, updateException);
        }
    }

    private RecoveryTaskSnapshot save(RecoveryTaskSnapshot task, RecoveryTaskStatus status, Integer totalComics,
                                      Integer recoveredComics, Integer skippedComics, Integer placeholderComics,
                                      Integer errorComics, String errorMessage, String errorDetails,
                                      LocalDateTime startedAt, LocalDateTime endedAt) {
        RecoveryTaskSnapshot updatedTask = new RecoveryTaskSnapshot(task.id(), task.managementTaskId(), status,
                totalComics, recoveredComics, skippedComics, placeholderComics, errorComics, errorMessage,
                errorDetails, task.retryCount(), task.createdAt(), startedAt, endedAt);
        persistencePort.update(new RecoveryTaskPersistencePort.UpdateCommand(updatedTask.id(),
                updatedTask.managementTaskId(), updatedTask.status(), updatedTask.totalComics(),
                updatedTask.recoveredComics(), updatedTask.skippedComics(), updatedTask.placeholderComics(),
                updatedTask.errorComics(), updatedTask.errorMessage(), updatedTask.errorDetails(),
                updatedTask.retryCount(), updatedTask.startedAt(), updatedTask.endedAt()));
        return updatedTask;
    }

    private ManagementTaskItemResponse syncItem(Long taskId, ManagementTaskStatus status,
            String errorMessage, String refType, Long refId) {
        var item = managementTaskService.findActiveItem(TARGET_TYPE_SYSTEM, taskId, TaskType.RECOVERY);
        return item == null ? null : managementTaskService.updateItemStatus(item.id(), status,
                errorMessage, refType, refId);
    }

    private boolean isProcessed(String key) {
        try { return Boolean.TRUE.equals(redisTemplate.hasKey(key)); }
        catch (RedisSystemException exception) { log.warn("幂等标记读取失败: key={}", key, exception); return false; }
    }

    private void markProcessed(String key) {
        try { redisTemplate.opsForValue().set(key, "1", IDEMPOTENCY_TTL); }
        catch (RedisSystemException exception) { log.warn("幂等标记写入失败: key={}", key, exception); }
    }
}
