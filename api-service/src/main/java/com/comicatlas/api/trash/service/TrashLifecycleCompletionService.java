package com.comicatlas.api.trash.service;

import com.comicatlas.common.event.ManagementCommandCompletedEvent;

/** 回收生命周期结果服务契约。 */
public interface TrashLifecycleCompletionService {
    void applyComicTrashCompleted(Long comicId);
    void applyChapterTrashCompleted(Long chapterId);
    void applyMediaTrashCompleted(ManagementCommandCompletedEvent event);
    void applyComicRestoreCompleted(Long comicId);
    void applyChapterRestoreCompleted(Long chapterId);
    void applyMediaRestoreCompleted(Long mediaId);
    void applyComicPurgeCompleted(Long comicId);
    void applyChapterPurgeCompleted(Long chapterId);
    void applyMediaPurgeCompleted(Long mediaId);
    void applyTrashFailed(String targetType, Long targetId, Long taskId);
    void revertToTrashed(String targetType, Long targetId);
    void revertToReady(String targetType, Long targetId);
}
