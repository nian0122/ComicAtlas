package com.comicatlas.api.metadata.application.service;

import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.application.port.out.TaskLifecyclePolicy;
import com.comicatlas.api.metadata.application.port.out.MetadataRefreshTaskPersistencePort;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 元数据刷新对任务生命周期的业务策略，隔离 task 通用服务与 comic 领域状态。 */
@Service
@RequiredArgsConstructor
public class MetadataRefreshTaskPolicy implements TaskLifecyclePolicy {
    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";

    private final MetadataRefreshTaskPersistencePort persistencePort;

    @Override
    public void lockOnCreate(TaskType taskType, List<TaskLifecyclePolicy.TaskTarget> targets) {
        if (taskType != TaskType.METADATA_REFRESH || targets == null || targets.isEmpty()) {
            return;
        }
        for (Long comicId : resolveFromTargets(taskType, targets)) {
            int rows = persistencePort.lockComicForRefresh(comicId);
            if (rows == 0) {
                throw new ConflictException(String.format(
                        "漫画 %d 不是 READY 或已被其他任务占用，无法创建元数据刷新任务", comicId));
            }
        }
    }

    @Override
    public void releaseAfterCancel(TaskType taskType, long activeItems,
                                   List<TaskLifecyclePolicy.TaskItemReference> items) {
        if (taskType != TaskType.METADATA_REFRESH || activeItems > 0) {
            return;
        }
        for (Long comicId : resolveFromItems(items)) {
            persistencePort.releaseComicRefresh(comicId);
        }
    }

    @Override
    public void prepareRetry(TaskType taskType, List<TaskLifecyclePolicy.TaskItemReference> items) {
        if (taskType != TaskType.METADATA_REFRESH) {
            return;
        }
        List<TaskLifecyclePolicy.TaskItemReference> retriedItems = items.stream()
                .filter(item -> item.status() == ManagementTaskStatus.FAILED
                        || item.status() == ManagementTaskStatus.CANCELLED).toList();
        for (Long comicId : resolveFromItems(retriedItems)) {
            int rows = persistencePort.lockComicForRefresh(comicId);
            if (rows == 0) {
                throw new ConflictException(String.format(
                        "漫画 %d 不是 READY 或已被其他任务占用，无法重试元数据刷新", comicId));
            }
        }
    }

    private Set<Long> resolveFromTargets(TaskType taskType,
            List<TaskLifecyclePolicy.TaskTarget> targets) {
        Set<Long> comicIds = new LinkedHashSet<>();
        List<Long> chapterIds = targets.stream()
                .filter(target -> effectiveType(target.operationType(), taskType) == TaskType.METADATA_REFRESH)
                .filter(target -> TARGET_TYPE_CHAPTER.equals(target.targetType()))
                .map(TaskLifecyclePolicy.TaskTarget::targetId).toList();
        targets.stream()
                .filter(target -> effectiveType(target.operationType(), taskType) == TaskType.METADATA_REFRESH)
                .filter(target -> TARGET_TYPE_COMIC.equals(target.targetType()))
                .map(TaskLifecyclePolicy.TaskTarget::targetId).forEach(comicIds::add);
        addChapterComicIds(chapterIds, comicIds);
        return comicIds;
    }

    private Set<Long> resolveFromItems(List<TaskLifecyclePolicy.TaskItemReference> items) {
        Set<Long> comicIds = new LinkedHashSet<>();
        List<Long> chapterIds = items.stream().filter(item -> item.operationType() == TaskType.METADATA_REFRESH)
                .filter(item -> TARGET_TYPE_CHAPTER.equals(item.targetType()))
                .map(TaskLifecyclePolicy.TaskItemReference::targetId).toList();
        items.stream().filter(item -> item.operationType() == TaskType.METADATA_REFRESH)
                .filter(item -> TARGET_TYPE_COMIC.equals(item.targetType()))
                .map(TaskLifecyclePolicy.TaskItemReference::targetId).forEach(comicIds::add);
        addChapterComicIds(chapterIds, comicIds);
        return comicIds;
    }

    private void addChapterComicIds(List<Long> chapterIds, Set<Long> comicIds) {
        if (chapterIds.isEmpty()) {
            return;
        }
        List<Long> resolvedComicIds = persistencePort.findComicIdsByChapterIds(chapterIds);
        if (resolvedComicIds.size() != new LinkedHashSet<>(chapterIds).size()) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "元数据刷新包含不存在的章节");
        }
        resolvedComicIds.forEach(comicIds::add);
    }

    private TaskType effectiveType(TaskType operationType, TaskType taskType) {
        return operationType != null ? operationType : taskType;
    }
}
