package com.comicatlas.api.task.application.service.impl;

import com.comicatlas.api.task.application.service.TaskAggregationService;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository.ManagementTaskAggregationSnapshot;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository.ManagementTaskAggregationUpdate;
import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository.ManagementTaskItemSnapshot;
import com.comicatlas.api.task.domain.service.ManagementTaskStatusAggregator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 根据任务项状态聚合管理任务进度、计数和终态。 */
@Service
@RequiredArgsConstructor
public class TaskAggregationServiceImpl implements TaskAggregationService {
    private final ManagementTaskAggregationRepository aggregationRepository;

    /** 重新聚合任务状态；调用方应在已有事务中调用。 */
    @Override
    public void aggregate(Long taskId) {
        ManagementTaskAggregationSnapshot snapshot = aggregationRepository.findByTaskId(taskId).orElse(null);
        if (snapshot == null) {
            return;
        }

        List<ManagementTaskItemSnapshot> items = snapshot.items();
        long successCount = count(items, ManagementTaskStatus.SUCCEEDED);
        long failureCount = count(items, ManagementTaskStatus.FAILED);
        long cancelledCount = count(items, ManagementTaskStatus.CANCELLED);
        long totalCount = items.size();
        int progress = totalCount == 0
                ? 0 : (int) ((successCount + failureCount + cancelledCount) * 100 / totalCount);
        boolean hasRunning = items.stream().anyMatch(item -> item.status() == ManagementTaskStatus.RUNNING
                || item.status() == ManagementTaskStatus.CANCELLING);
        boolean hasQueued = items.stream().anyMatch(item -> item.status() == ManagementTaskStatus.QUEUED);
        ManagementTaskStatus aggregatedStatus = ManagementTaskStatusAggregator.aggregate(
                snapshot.status(), totalCount, successCount, failureCount, cancelledCount,
                hasRunning, hasQueued);

        LocalDateTime updateTime = LocalDateTime.now();
        LocalDateTime startedAt = null;
        LocalDateTime completedAt = null;
        if (aggregatedStatus != snapshot.status()) {
            if (aggregatedStatus.isTerminal()) {
                completedAt = updateTime;
            } else if (aggregatedStatus == ManagementTaskStatus.RUNNING) {
                startedAt = updateTime;
            }
        }
        String aggregatedError = aggregatedStatus == ManagementTaskStatus.FAILED
                || aggregatedStatus == ManagementTaskStatus.PARTIALLY_SUCCEEDED
                ? items.stream().filter(item -> item.status() == ManagementTaskStatus.FAILED)
                .map(ManagementTaskItemSnapshot::errorMessage)
                .filter(message -> message != null && !message.isBlank())
                .findFirst().orElse(null) : null;

        aggregationRepository.update(taskId, new ManagementTaskAggregationUpdate(
                aggregatedStatus, (int) successCount, (int) failureCount, (int) cancelledCount,
                progress, aggregatedError, updateTime, startedAt, completedAt));
    }

    private long count(List<ManagementTaskItemSnapshot> items, ManagementTaskStatus status) {
        return items.stream().filter(item -> item.status() == status).count();
    }
}
