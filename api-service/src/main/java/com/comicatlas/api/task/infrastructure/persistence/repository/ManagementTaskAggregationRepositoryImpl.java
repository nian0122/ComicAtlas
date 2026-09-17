package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.comicatlas.api.task.domain.repository.ManagementTaskAggregationRepository;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 管理任务聚合持久化适配器，将数据库实体转换为领域端口数据。 */
@Repository
@RequiredArgsConstructor
public class ManagementTaskAggregationRepositoryImpl implements ManagementTaskAggregationRepository {
    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;

    @Override
    public Optional<ManagementTaskAggregationSnapshot> findByTaskId(Long taskId) {
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return Optional.empty();
        }
        List<ManagementTaskItemSnapshot> items = itemMapper.selectByTaskId(taskId).stream()
                .map(item -> new ManagementTaskItemSnapshot(item.getStatus(), item.getErrorMessage()))
                .toList();
        return Optional.of(new ManagementTaskAggregationSnapshot(
                task.getId(), task.getStatus(), task.getStartedAt(), items));
    }

    @Override
    public void update(Long taskId, ManagementTaskAggregationUpdate update) {
        ManagementTask task = new ManagementTask();
        task.setId(taskId);
        task.setStatus(update.status());
        task.setSuccessCount(update.successCount());
        task.setFailureCount(update.failureCount());
        task.setCancelledCount(update.cancelledCount());
        task.setProgress(update.progress());
        task.setErrorMessage(update.errorMessage());
        task.setUpdatedAt(update.updatedAt());
        if (update.startedAt() != null) {
            task.setStartedAt(update.startedAt());
        }
        if (update.completedAt() != null) {
            task.setCompletedAt(update.completedAt());
        }
        taskMapper.updateById(task);
    }
}
