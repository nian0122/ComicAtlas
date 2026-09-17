package com.comicatlas.api.task.domain.repository;

import com.comicatlas.api.task.domain.model.ManagementTaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 管理任务聚合读写端口。领域层不暴露 MyBatis Entity。 */
public interface ManagementTaskAggregationRepository {
    Optional<ManagementTaskAggregationSnapshot> findByTaskId(Long taskId);

    void update(Long taskId, ManagementTaskAggregationUpdate update);

    record ManagementTaskAggregationSnapshot(Long taskId, ManagementTaskStatus status,
                                              LocalDateTime startedAt,
                                              List<ManagementTaskItemSnapshot> items) {
        public ManagementTaskAggregationSnapshot {
            items = List.copyOf(items);
        }
    }

    record ManagementTaskItemSnapshot(ManagementTaskStatus status, String errorMessage) {
    }

    record ManagementTaskAggregationUpdate(ManagementTaskStatus status, int successCount,
                                           int failureCount, int cancelledCount, int progress,
                                           String errorMessage, LocalDateTime updatedAt,
                                           LocalDateTime startedAt, LocalDateTime completedAt) {
    }
}
