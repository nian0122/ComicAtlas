package com.comicatlas.api.task.application.port.out;

import com.comicatlas.api.shared.application.model.PageResult;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;

import java.util.List;
import java.time.LocalDateTime;

/** 任务查询应用服务访问任务与目标摘要数据的输出端口。 */
public interface TaskQueryPersistencePort {

    List<Long> findTaskIdsByComicId(Long comicId);

    PageResult<TaskSnapshot> findPage(int page, int size, String taskType, String status,
                                    String batchId, String targetType, List<Long> taskIds);

    TaskSnapshot findTask(Long taskId);

    List<ItemSnapshot> findItemsByTaskId(Long taskId);

    List<ItemSnapshot> findItemsByTaskIds(List<Long> taskIds);

    TaskSnapshot findByIdempotencyKey(String idempotencyKey);

    ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType);

    long countActiveItems(Long taskId);

    long countActiveMetadataItems(Long taskId, Long comicId);

    Long insertTask(CreateTaskCommand command);

    int updateTask(UpdateTaskCommand command);

    long countItemsByLockKey(String lockKey);

    Long insertTaskItem(CreateItemCommand command);

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

    ItemSnapshot findItem(Long itemId);

    List<ComicSnapshot> findComicsByIds(List<Long> comicIds);

    List<ChapterSnapshot> findChaptersByIds(List<Long> chapterIds);

    List<MediaSnapshot> findMediaByIds(List<Long> mediaIds);

    record ComicSnapshot(Long id, String title) {
    }

    record ChapterSnapshot(Long id, Long comicId) {
    }

    record MediaSnapshot(Long id, Long chapterId) {
    }

    record TaskSnapshot(Long id, TaskType taskType, String operation, String targetType,
                        String batchId, Boolean batch, ManagementTaskStatus status, String stage,
                        Integer progress, Integer totalCount, Integer successCount, Integer failureCount,
                        Integer cancelledCount, String errorMessage, Integer attempt, Integer version,
                        String idempotencyPayloadHash, LocalDateTime createdAt, LocalDateTime updatedAt,
                        LocalDateTime startedAt, LocalDateTime completedAt) {
    }

    record ItemSnapshot(Long id, Long taskId, String targetType, Long targetId, TaskType operationType,
                        ManagementTaskStatus status, Integer attempt, Integer progress, String lockKey,
                        String resultRefType, Long resultRefId, String errorMessage, Integer version,
                        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime startedAt,
                        LocalDateTime completedAt) {
    }

    record CreateTaskCommand(TaskType taskType, String operation, String targetType, String batchId,
                             Boolean batch, ManagementTaskStatus status, Integer progress, Integer attempt,
                             String idempotencyKey, String idempotencyPayloadHash, Integer totalCount,
                             Integer successCount, Integer failureCount, Integer cancelledCount) {
    }

    record UpdateTaskCommand(Long id, ManagementTaskStatus status, LocalDateTime updatedAt) {
    }

    record CreateItemCommand(Long taskId, String targetType, Long targetId, TaskType operationType,
                             ManagementTaskStatus status, Integer attempt, Integer progress, String lockKey) {
    }
}
