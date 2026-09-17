package com.comicatlas.api.task.application.port.out;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;

import java.time.LocalDateTime;
import java.util.List;

/** 管理任务查询视图所需的只读快照端口。 */
public interface TaskViewQueryPort {
    IPage<TaskSnapshot> findTaskPage(int page, int size, String taskType, String status,
                                     String batchId, String targetType, List<Long> taskIds);

    TaskSnapshot findTaskView(Long taskId);

    List<ItemSnapshot> findTaskItemsById(Long taskId);

    List<ItemSnapshot> findTaskItemsByIds(List<Long> taskIds);

    List<ComicSnapshot> findComicViewsByIds(List<Long> comicIds);

    List<ChapterSnapshot> findChapterViewsByIds(List<Long> chapterIds);

    List<MediaSnapshot> findMediaViewsByIds(List<Long> mediaIds);

    record TaskSnapshot(Long id, TaskType taskType, String operation, String targetType,
                        String batchId, Boolean batch, ManagementTaskStatus status, String stage,
                        Integer progress, Integer totalCount, Integer successCount, Integer failureCount,
                        Integer cancelledCount, String errorMessage, Integer attempt, Integer version,
                        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime startedAt,
                        LocalDateTime completedAt) { }

    record ItemSnapshot(Long id, Long taskId, String targetType, Long targetId, TaskType operationType,
                        ManagementTaskStatus status, Integer attempt, Integer progress, String resultRefType,
                        Long resultRefId, String errorMessage, Integer version, LocalDateTime createdAt,
                        LocalDateTime updatedAt, LocalDateTime startedAt, LocalDateTime completedAt) { }

    record ComicSnapshot(Long id, String title) { }

    record ChapterSnapshot(Long id, Long comicId) { }

    record MediaSnapshot(Long id, Long chapterId) { }
}
