package com.comicatlas.api.task.service.impl;

// 条件更新由任务聚合服务维护状态机与事务边界，Mapper 执行参数化更新。
// 架构说明：Service 直接构造 LambdaUpdateWrapper 聚合更新任务状态；条件更新应收口到 ManagementTaskMapper。
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskMapper;
import com.comicatlas.api.task.service.TaskAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 根据任务项状态聚合管理任务进度、计数和终态。 */
@Service
@RequiredArgsConstructor
public class TaskAggregationServiceImpl implements TaskAggregationService {
    // 任务聚合契约由应用服务公开，具体实现保持在任务业务包内。

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;

    /**
     * 重新聚合任务状态。调用方应在已有事务中调用，确保 item 与主任务状态一致提交。
     */
    public void aggregate(Long taskId) {
        List<ManagementTaskItem> items = itemMapper.selectByTaskId(taskId);
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        long successCount = count(items, ManagementTaskStatus.SUCCEEDED);
        long failureCount = count(items, ManagementTaskStatus.FAILED);
        long cancelledCount = count(items, ManagementTaskStatus.CANCELLED);
        task.setSuccessCount((int) successCount);
        task.setFailureCount((int) failureCount);
        task.setCancelledCount((int) cancelledCount);

        long total = items.size();
        if (total > 0) {
            task.setProgress((int) ((successCount + failureCount + cancelledCount) * 100 / total));
        }

        boolean hasRunning = items.stream().anyMatch(item -> item.getStatus() == ManagementTaskStatus.RUNNING
                || item.getStatus() == ManagementTaskStatus.CANCELLING);
        boolean hasQueued = items.stream().anyMatch(item -> item.getStatus() == ManagementTaskStatus.QUEUED);
        updateTaskStatus(task, total, successCount, failureCount, cancelledCount, hasRunning, hasQueued);

        task.setUpdatedAt(LocalDateTime.now());
        String aggregatedError = task.getStatus() == ManagementTaskStatus.FAILED
                || task.getStatus() == ManagementTaskStatus.PARTIALLY_SUCCEEDED
                ? items.stream().filter(item -> item.getStatus() == ManagementTaskStatus.FAILED)
                .map(ManagementTaskItem::getErrorMessage)
                .filter(message -> message != null && !message.isBlank())
                .findFirst().orElse(null) : null;
        task.setErrorMessage(aggregatedError);
        taskMapper.updateById(task);
    }

    private long count(List<ManagementTaskItem> items, ManagementTaskStatus status) {
        return items.stream().filter(item -> item.getStatus() == status).count();
    }

    private void updateTaskStatus(ManagementTask task, long total, long successCount,
                                  long failureCount, long cancelledCount,
                                  boolean hasRunning, boolean hasQueued) {
        if (task.getStatus() == ManagementTaskStatus.CANCELLING && !hasRunning && !hasQueued) {
            task.setStatus(ManagementTaskStatus.CANCELLED);
            task.setCompletedAt(LocalDateTime.now());
        } else if (!hasRunning && !hasQueued) {
            task.setStatus(terminalStatus(total, successCount, failureCount, cancelledCount));
            task.setCompletedAt(LocalDateTime.now());
        } else if ((hasRunning || task.getStatus() == ManagementTaskStatus.RUNNING)
                && task.getStatus() == ManagementTaskStatus.QUEUED) {
            task.setStatus(ManagementTaskStatus.RUNNING);
            task.setStartedAt(LocalDateTime.now());
        }
    }

    private ManagementTaskStatus terminalStatus(long total, long successCount,
                                                  long failureCount, long cancelledCount) {
        if (successCount == total) {
            return ManagementTaskStatus.SUCCEEDED;
        }
        if (failureCount == total) {
            return ManagementTaskStatus.FAILED;
        }
        if (cancelledCount == total) {
            return ManagementTaskStatus.CANCELLED;
        }
        return ManagementTaskStatus.PARTIALLY_SUCCEEDED;
    }
}
