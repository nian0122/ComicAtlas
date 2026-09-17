package com.comicatlas.api.metadata.application.port.out;

import com.comicatlas.api.task.domain.model.TaskType;
import java.time.LocalDateTime;
import java.util.Optional;

/** 元数据刷新完成事件的并发状态持久化输出端口。 */
public interface MetadataRefreshCompletionPersistencePort {
    ItemSnapshot findItem(Long itemId);
    void lockComic(Long comicId);
    int markSucceededIfActive(Long itemId, int attempt, LocalDateTime completedAt, LocalDateTime updatedAt);
    int markFailedIfActive(Long itemId, int attempt, String errorMessage,
                           LocalDateTime completedAt, LocalDateTime updatedAt);
    int markRefreshCompleted(Long comicId);
    Optional<Long> findChapterComicId(Long chapterId);

    record ItemSnapshot(Long id, String targetType, Long targetId, TaskType operationType,
                        String status, Integer attempt) {
        public boolean isTerminal() {
            return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }
}
