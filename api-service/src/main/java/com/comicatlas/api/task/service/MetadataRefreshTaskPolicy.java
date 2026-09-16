package com.comicatlas.api.task.service;

// 架构说明：Service 策略直接构造 LambdaUpdateWrapper 更新漫画状态；条件更新应收口到 ComicMapper。
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.task.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 元数据刷新对任务生命周期的业务策略，隔离 task 通用服务与 comic 领域状态。 */
@Service
@RequiredArgsConstructor
public class MetadataRefreshTaskPolicy {
    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";

    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;

    public void lockOnCreate(TaskType taskType, List<CreateManagementTaskRequest.TaskTarget> targets) {
        if (taskType != TaskType.METADATA_REFRESH || targets == null || targets.isEmpty()) {
            return;
        }
        for (Long comicId : resolveFromTargets(taskType, targets)) {
            int rows = comicMapper.lockForMetadataRefresh(comicId);
            if (rows == 0) {
                throw new ConflictException(String.format(
                        "漫画 %d 不是 READY 或已被其他任务占用，无法创建元数据刷新任务", comicId));
            }
        }
    }

    public void releaseAfterCancel(ManagementTask task, Long taskId,
                                   long activeItems, List<ManagementTaskItem> items) {
        if (task.getTaskType() != TaskType.METADATA_REFRESH || activeItems > 0) {
            return;
        }
        for (Long comicId : resolveFromItems(items)) {
            comicMapper.releaseMetadataRefresh(comicId);
        }
    }

    public void prepareRetry(TaskType taskType, List<ManagementTaskItem> items) {
        if (taskType != TaskType.METADATA_REFRESH) {
            return;
        }
        List<ManagementTaskItem> retriedItems = items.stream()
                .filter(item -> item.getStatus() == ManagementTaskStatus.FAILED
                        || item.getStatus() == ManagementTaskStatus.CANCELLED).toList();
        for (Long comicId : resolveFromItems(retriedItems)) {
            int rows = comicMapper.lockForMetadataRefresh(comicId);
            if (rows == 0) {
                throw new ConflictException(String.format(
                        "漫画 %d 不是 READY 或已被其他任务占用，无法重试元数据刷新", comicId));
            }
        }
    }

    private Set<Long> resolveFromTargets(TaskType taskType,
            List<CreateManagementTaskRequest.TaskTarget> targets) {
        Set<Long> comicIds = new LinkedHashSet<>();
        List<Long> chapterIds = targets.stream()
                .filter(target -> effectiveType(target.getOperationType(), taskType) == TaskType.METADATA_REFRESH)
                .filter(target -> TARGET_TYPE_CHAPTER.equals(target.getTargetType()))
                .map(CreateManagementTaskRequest.TaskTarget::getTargetId).toList();
        targets.stream()
                .filter(target -> effectiveType(target.getOperationType(), taskType) == TaskType.METADATA_REFRESH)
                .filter(target -> TARGET_TYPE_COMIC.equals(target.getTargetType()))
                .map(CreateManagementTaskRequest.TaskTarget::getTargetId).forEach(comicIds::add);
        addChapterComicIds(chapterIds, comicIds);
        return comicIds;
    }

    private Set<Long> resolveFromItems(List<ManagementTaskItem> items) {
        Set<Long> comicIds = new LinkedHashSet<>();
        List<Long> chapterIds = items.stream().filter(item -> item.getOperationType() == TaskType.METADATA_REFRESH)
                .filter(item -> TARGET_TYPE_CHAPTER.equals(item.getTargetType()))
                .map(ManagementTaskItem::getTargetId).toList();
        items.stream().filter(item -> item.getOperationType() == TaskType.METADATA_REFRESH)
                .filter(item -> TARGET_TYPE_COMIC.equals(item.getTargetType()))
                .map(ManagementTaskItem::getTargetId).forEach(comicIds::add);
        addChapterComicIds(chapterIds, comicIds);
        return comicIds;
    }

    private void addChapterComicIds(List<Long> chapterIds, Set<Long> comicIds) {
        if (chapterIds.isEmpty()) {
            return;
        }
        List<Chapter> chapters = chapterMapper.selectBatchIds(chapterIds);
        if (chapters.size() != new LinkedHashSet<>(chapterIds).size()) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "元数据刷新包含不存在的章节");
        }
        chapters.stream().map(Chapter::getComicId).forEach(comicIds::add);
    }

    private TaskType effectiveType(TaskType operationType, TaskType taskType) {
        return operationType != null ? operationType : taskType;
    }
}
