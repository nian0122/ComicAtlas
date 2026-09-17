package com.comicatlas.api.recovery.application.port.out;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.recovery.domain.model.RecoveryTaskStatus;

import java.time.LocalDateTime;

/** 恢复任务应用服务访问持久化层的输出端口。 */
public interface RecoveryTaskPersistencePort {

    IPage<RecoveryTaskSnapshot> findPage(int page, int size);

    RecoveryTaskSnapshot findById(Long taskId);

    long countActiveTasks();

    Long insert(CreateCommand command);

    void update(UpdateCommand command);

    record RecoveryTaskSnapshot(Long id, Long managementTaskId, RecoveryTaskStatus status,
                                Integer totalComics, Integer recoveredComics, Integer skippedComics,
                                Integer placeholderComics, Integer errorComics, String errorMessage,
                                String errorDetails, Integer retryCount, LocalDateTime createdAt,
                                LocalDateTime startedAt, LocalDateTime endedAt) { }

    record CreateCommand(RecoveryTaskStatus status, Integer totalComics, Integer recoveredComics,
                         Integer skippedComics, Integer placeholderComics, Integer errorComics,
                         Integer retryCount) { }

    record UpdateCommand(Long id, Long managementTaskId, RecoveryTaskStatus status, Integer totalComics,
                         Integer recoveredComics, Integer skippedComics, Integer placeholderComics,
                         Integer errorComics, String errorMessage, String errorDetails, Integer retryCount,
                         LocalDateTime startedAt, LocalDateTime endedAt) { }
}
