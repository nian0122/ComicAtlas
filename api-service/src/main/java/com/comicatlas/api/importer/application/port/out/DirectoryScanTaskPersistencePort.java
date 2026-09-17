package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.domain.model.DirectoryScanTaskStatus;
import java.time.LocalDateTime;

/** 目录扫描任务持久化输出端口。 */
public interface DirectoryScanTaskPersistencePort {
    Long insert(CreateCommand command);
    Snapshot findById(Long taskId);
    int update(UpdateCommand command);

    record Snapshot(Long id, Long managementTaskId, DirectoryScanTaskStatus status, String directoryPath,
                    Integer totalItems, String resultJson, String errorMessage, Integer retryCount,
                    LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime endedAt) {
    }

    record CreateCommand(DirectoryScanTaskStatus status, String directoryPath, Integer totalItems,
                         Integer retryCount) {
    }

    record UpdateCommand(Long id, Long managementTaskId, DirectoryScanTaskStatus status, String directoryPath,
                         Integer totalItems, String resultJson, String errorMessage, Integer retryCount,
                         LocalDateTime startedAt, LocalDateTime endedAt) {
    }
}
