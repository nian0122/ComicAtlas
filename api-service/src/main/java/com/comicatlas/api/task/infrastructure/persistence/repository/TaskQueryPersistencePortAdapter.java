package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.LocalDateTime;

/** 任务查询输出端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class TaskQueryPersistencePortAdapter implements TaskQueryPersistencePort {

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override
    public List<Long> findTaskIdsByComicId(Long comicId) { return itemMapper.selectTaskIdsByComicId(comicId); }

    @Override
    public IPage<ManagementTask> findPage(int page, int size, String taskType, String status,
                                          String batchId, String targetType, List<Long> taskIds) {
        return taskMapper.selectPageByCondition(new Page<>(page, size), taskType, status,
                batchId, targetType, taskIds);
    }

    @Override
    public ManagementTask findTask(Long taskId) { return taskMapper.selectById(taskId); }

    @Override
    public List<ManagementTaskItem> findItemsByTaskId(Long taskId) { return itemMapper.selectByTaskId(taskId); }

    @Override
    public List<ManagementTaskItem> findItemsByTaskIds(List<Long> taskIds) {
        return itemMapper.selectByTaskIds(taskIds);
    }

    @Override
    public ManagementTask findByIdempotencyKey(String idempotencyKey) {
        return taskMapper.selectByIdempotencyKey(idempotencyKey);
    }

    @Override
    public ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType) {
        return itemMapper.selectActiveByTarget(targetType, targetId, operationType.name());
    }

    @Override
    public long countActiveItems(Long taskId) { return itemMapper.countActiveByTaskId(taskId); }

    @Override
    public long countActiveMetadataItems(Long taskId, Long comicId) {
        return itemMapper.countActiveMetadataItems(taskId, comicId);
    }

    @Override
    public void insertTask(ManagementTask task) { taskMapper.insert(task); }

    @Override
    public void updateTask(ManagementTask task) { taskMapper.updateById(task); }

    @Override
    public long countItemsByLockKey(String lockKey) { return itemMapper.countByLockKey(lockKey); }

    @Override
    public void insertTaskItem(ManagementTaskItem item) { itemMapper.insert(item); }

    @Override
    public int resetTask(Long taskId, int attempt, LocalDateTime updatedAt) {
        return taskMapper.resetForRetry(taskId, attempt, updatedAt);
    }

    @Override
    public int updateStage(Long taskId, String stage, Integer progress, boolean startTask,
                           LocalDateTime startedAt, LocalDateTime updatedAt) {
        return taskMapper.updateStage(taskId, stage, progress, startTask, startedAt, updatedAt);
    }

    @Override
    public int cancelQueuedItems(Long taskId, LocalDateTime completedAt, LocalDateTime updatedAt) {
        return itemMapper.cancelQueuedByTask(taskId, completedAt, updatedAt);
    }

    @Override
    public int resetItem(Long itemId, int attempt, String lockKey, LocalDateTime updatedAt) {
        return itemMapper.resetForRetry(itemId, attempt, lockKey, updatedAt);
    }

    @Override
    public int updateItemStatus(Long itemId, Integer attempt, String newStatus, String errorMessage,
                                String resultRefType, Long resultRefId, LocalDateTime startedAt,
                                LocalDateTime completedAt, LocalDateTime updatedAt) {
        return itemMapper.updateStatusIfActive(itemId, attempt, newStatus, errorMessage,
                resultRefType, resultRefId, startedAt, completedAt, updatedAt);
    }

    @Override
    public int updateItemProgress(Long itemId, Integer attempt, int progress, boolean startItem,
                                  LocalDateTime startedAt, LocalDateTime updatedAt) {
        return itemMapper.updateProgressIfActive(itemId, attempt, progress, startItem, startedAt, updatedAt);
    }

    @Override
    public ManagementTaskItem findItem(Long itemId) { return itemMapper.selectById(itemId); }

    @Override
    public List<TaskQueryPersistencePort.ComicSnapshot> findComicsByIds(List<Long> comicIds) {
        return comicMapper.selectBatchIds(comicIds).stream()
                .map(comic -> new TaskQueryPersistencePort.ComicSnapshot(comic.getId(), comic.getTitle()))
                .toList();
    }

    @Override
    public List<TaskQueryPersistencePort.ChapterSnapshot> findChaptersByIds(List<Long> chapterIds) {
        return chapterMapper.selectBatchIds(chapterIds).stream()
                .map(chapter -> new TaskQueryPersistencePort.ChapterSnapshot(
                        chapter.getId(), chapter.getComicId()))
                .toList();
    }

    @Override
    public List<TaskQueryPersistencePort.MediaSnapshot> findMediaByIds(List<Long> mediaIds) {
        return mediaMapper.selectBatchIds(mediaIds).stream()
                .map(media -> new TaskQueryPersistencePort.MediaSnapshot(
                        media.getId(), media.getChapterId()))
                .toList();
    }
}
