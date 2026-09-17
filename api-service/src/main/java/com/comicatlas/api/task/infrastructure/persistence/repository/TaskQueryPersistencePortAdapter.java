package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import com.comicatlas.api.task.application.port.out.TaskViewQueryPort;
import com.comicatlas.api.shared.application.model.PageResult;
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
public class TaskQueryPersistencePortAdapter implements TaskQueryPersistencePort, TaskViewQueryPort {

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    @Override
    public PageResult<TaskViewQueryPort.TaskSnapshot> findTaskPage(int page, int size, String taskType, String status,
                                            String batchId, String targetType, List<Long> taskIds) {
        IPage<ManagementTask> sourcePage = taskMapper.selectPageByCondition(new Page<>(page, size), taskType, status,
                batchId, targetType, taskIds);
        return new PageResult<>(sourcePage.getCurrent(), sourcePage.getSize(), sourcePage.getTotal(),
                sourcePage.getRecords().stream().map(this::toTaskSnapshot).toList());
    }

    @Override public TaskViewQueryPort.TaskSnapshot findTaskView(Long taskId) {
        ManagementTask task = findTaskEntity(taskId);
        return task == null ? null : toTaskSnapshot(task);
    }

    @Override public List<TaskViewQueryPort.ItemSnapshot> findTaskItemsById(Long taskId) {
        return itemMapper.selectByTaskId(taskId).stream().map(this::toItemSnapshot).toList();
    }

    @Override public List<TaskViewQueryPort.ItemSnapshot> findTaskItemsByIds(List<Long> taskIds) {
        return itemMapper.selectByTaskIds(taskIds).stream().map(this::toItemSnapshot).toList();
    }

    @Override public List<TaskViewQueryPort.ComicSnapshot> findComicViewsByIds(List<Long> comicIds) {
        return comicMapper.selectBatchIds(comicIds).stream()
                .map(comic -> new TaskViewQueryPort.ComicSnapshot(comic.getId(), comic.getTitle())).toList();
    }

    @Override public List<TaskViewQueryPort.ChapterSnapshot> findChapterViewsByIds(List<Long> chapterIds) {
        return chapterMapper.selectBatchIds(chapterIds).stream()
                .map(chapter -> new TaskViewQueryPort.ChapterSnapshot(chapter.getId(), chapter.getComicId())).toList();
    }

    @Override public List<TaskViewQueryPort.MediaSnapshot> findMediaViewsByIds(List<Long> mediaIds) {
        return mediaMapper.selectBatchIds(mediaIds).stream()
                .map(media -> new TaskViewQueryPort.MediaSnapshot(media.getId(), media.getChapterId())).toList();
    }

    private ManagementTask findTaskEntity(Long taskId) { return taskMapper.selectById(taskId); }

    private TaskViewQueryPort.TaskSnapshot toTaskSnapshot(ManagementTask task) {
        return new TaskViewQueryPort.TaskSnapshot(task.getId(), task.getTaskType(), task.getOperation(),
                task.getTargetType(), task.getBatchId(), task.getBatch(), task.getStatus(), task.getStage(),
                task.getProgress(), task.getTotalCount(), task.getSuccessCount(), task.getFailureCount(),
                task.getCancelledCount(), task.getErrorMessage(), task.getAttempt(), task.getVersion(),
                task.getCreatedAt(), task.getUpdatedAt(), task.getStartedAt(), task.getCompletedAt());
    }

    private TaskViewQueryPort.ItemSnapshot toItemSnapshot(ManagementTaskItem item) {
        return new TaskViewQueryPort.ItemSnapshot(item.getId(), item.getTaskId(), item.getTargetType(),
                item.getTargetId(), item.getOperationType(), item.getStatus(), item.getAttempt(), item.getProgress(),
                item.getResultRefType(), item.getResultRefId(), item.getErrorMessage(), item.getVersion(),
                item.getCreatedAt(), item.getUpdatedAt(), item.getStartedAt(), item.getCompletedAt());
    }
    @Override
    public List<Long> findTaskIdsByComicId(Long comicId) { return itemMapper.selectTaskIdsByComicId(comicId); }

    @Override
    public PageResult<TaskQueryPersistencePort.TaskSnapshot> findPage(int page, int size, String taskType, String status,
                                          String batchId, String targetType, List<Long> taskIds) {
        IPage<ManagementTask> sourcePage = taskMapper.selectPageByCondition(new Page<>(page, size), taskType, status,
                batchId, targetType, taskIds);
        return new PageResult<>(sourcePage.getCurrent(), sourcePage.getSize(), sourcePage.getTotal(),
                sourcePage.getRecords().stream().map(this::toCommandTaskSnapshot).toList());
    }

    @Override
    public TaskQueryPersistencePort.TaskSnapshot findTask(Long taskId) {
        ManagementTask task = taskMapper.selectById(taskId);
        return task == null ? null : toCommandTaskSnapshot(task);
    }

    @Override
    public List<TaskQueryPersistencePort.ItemSnapshot> findItemsByTaskId(Long taskId) {
        return itemMapper.selectByTaskId(taskId).stream().map(this::toCommandItemSnapshot).toList();
    }

    @Override
    public List<TaskQueryPersistencePort.ItemSnapshot> findItemsByTaskIds(List<Long> taskIds) {
        return itemMapper.selectByTaskIds(taskIds).stream().map(this::toCommandItemSnapshot).toList();
    }

    @Override
    public TaskQueryPersistencePort.TaskSnapshot findByIdempotencyKey(String idempotencyKey) {
        ManagementTask task = taskMapper.selectByIdempotencyKey(idempotencyKey);
        return task == null ? null : toCommandTaskSnapshot(task);
    }

    @Override
    public TaskQueryPersistencePort.ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType) {
        ManagementTaskItem item = itemMapper.selectActiveByTarget(targetType, targetId, operationType.name());
        return item == null ? null : toCommandItemSnapshot(item);
    }

    @Override
    public long countActiveItems(Long taskId) { return itemMapper.countActiveByTaskId(taskId); }

    @Override
    public long countActiveMetadataItems(Long taskId, Long comicId) {
        return itemMapper.countActiveMetadataItems(taskId, comicId);
    }

    @Override
    public Long insertTask(TaskQueryPersistencePort.CreateTaskCommand command) {
        ManagementTask task = new ManagementTask();
        task.setTaskType(command.taskType()); task.setOperation(command.operation());
        task.setTargetType(command.targetType()); task.setBatchId(command.batchId()); task.setBatch(command.batch());
        task.setStatus(command.status()); task.setProgress(command.progress()); task.setAttempt(command.attempt());
        task.setIdempotencyKey(command.idempotencyKey());
        task.setIdempotencyPayloadHash(command.idempotencyPayloadHash());
        task.setTotalCount(command.totalCount()); task.setSuccessCount(command.successCount());
        task.setFailureCount(command.failureCount()); task.setCancelledCount(command.cancelledCount());
        taskMapper.insert(task);
        return task.getId();
    }

    @Override
    public int updateTask(TaskQueryPersistencePort.UpdateTaskCommand command) {
        ManagementTask task = new ManagementTask();
        task.setId(command.id()); task.setStatus(command.status()); task.setUpdatedAt(command.updatedAt());
        return taskMapper.updateById(task);
    }

    @Override
    public long countItemsByLockKey(String lockKey) { return itemMapper.countByLockKey(lockKey); }

    @Override
    public Long insertTaskItem(TaskQueryPersistencePort.CreateItemCommand command) {
        ManagementTaskItem item = new ManagementTaskItem();
        item.setTaskId(command.taskId()); item.setTargetType(command.targetType());
        item.setTargetId(command.targetId()); item.setOperationType(command.operationType());
        item.setStatus(command.status()); item.setAttempt(command.attempt());
        item.setProgress(command.progress()); item.setLockKey(command.lockKey());
        itemMapper.insert(item);
        return item.getId();
    }

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
    public TaskQueryPersistencePort.ItemSnapshot findItem(Long itemId) {
        ManagementTaskItem item = itemMapper.selectById(itemId);
        return item == null ? null : toCommandItemSnapshot(item);
    }

    private TaskQueryPersistencePort.TaskSnapshot toCommandTaskSnapshot(ManagementTask task) {
        return new TaskQueryPersistencePort.TaskSnapshot(task.getId(), task.getTaskType(), task.getOperation(),
                task.getTargetType(), task.getBatchId(), task.getBatch(), task.getStatus(), task.getStage(),
                task.getProgress(), task.getTotalCount(), task.getSuccessCount(), task.getFailureCount(),
                task.getCancelledCount(), task.getErrorMessage(), task.getAttempt(), task.getVersion(),
                task.getIdempotencyPayloadHash(), task.getCreatedAt(), task.getUpdatedAt(), task.getStartedAt(),
                task.getCompletedAt());
    }

    private TaskQueryPersistencePort.ItemSnapshot toCommandItemSnapshot(ManagementTaskItem item) {
        return new TaskQueryPersistencePort.ItemSnapshot(item.getId(), item.getTaskId(), item.getTargetType(),
                item.getTargetId(), item.getOperationType(), item.getStatus(), item.getAttempt(), item.getProgress(),
                item.getLockKey(), item.getResultRefType(), item.getResultRefId(), item.getErrorMessage(),
                item.getVersion(), item.getCreatedAt(), item.getUpdatedAt(), item.getStartedAt(), item.getCompletedAt());
    }

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
