package com.comicatlas.api.task.application.port.out;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.domain.model.TaskType;

import java.util.List;
import java.time.LocalDateTime;

/** 任务查询应用服务访问任务与目标摘要数据的输出端口。 */
public interface TaskQueryPersistencePort {

    List<Long> findTaskIdsByComicId(Long comicId);

    IPage<ManagementTask> findPage(int page, int size, String taskType, String status,
                                    String batchId, String targetType, List<Long> taskIds);

    ManagementTask findTask(Long taskId);

    List<ManagementTaskItem> findItemsByTaskId(Long taskId);

    List<ManagementTaskItem> findItemsByTaskIds(List<Long> taskIds);

    ManagementTask findByIdempotencyKey(String idempotencyKey);

    ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType);

    long countActiveItems(Long taskId);

    long countActiveMetadataItems(Long taskId, Long comicId);

    void insertTask(ManagementTask task);

    void updateTask(ManagementTask task);

    long countItemsByLockKey(String lockKey);

    void insertTaskItem(ManagementTaskItem item);

    int resetTask(Long taskId, int attempt, LocalDateTime updatedAt);

    int updateStage(Long taskId, String stage, Integer progress, boolean startTask,
                    LocalDateTime startedAt, LocalDateTime updatedAt);

    int cancelQueuedItems(Long taskId, LocalDateTime completedAt, LocalDateTime updatedAt);

    int resetItem(Long itemId, int attempt, String lockKey, LocalDateTime updatedAt);

    int updateItemStatus(Long itemId, Integer attempt, String newStatus, String errorMessage,
                         String resultRefType, Long resultRefId, LocalDateTime startedAt,
                         LocalDateTime completedAt, LocalDateTime updatedAt);

    int updateItemProgress(Long itemId, Integer attempt, int progress, boolean startItem,
                           LocalDateTime startedAt, LocalDateTime updatedAt);

    ManagementTaskItem findItem(Long itemId);

    List<ComicSnapshot> findComicsByIds(List<Long> comicIds);

    List<ChapterSnapshot> findChaptersByIds(List<Long> chapterIds);

    List<MediaSnapshot> findMediaByIds(List<Long> mediaIds);

    record ComicSnapshot(Long id, String title) {
    }

    record ChapterSnapshot(Long id, Long comicId) {
    }

    record MediaSnapshot(Long id, Long chapterId) {
    }
}
