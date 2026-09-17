package com.comicatlas.api.trash.application.port.in;

import com.comicatlas.api.task.interfaces.rest.dto.OperationSubmitResultDTO;
import com.comicatlas.api.trash.interfaces.rest.dto.TrashReconcileReport;

/** 回收生命周期操作服务契约。 */
public interface TrashLifecycleService {
    OperationSubmitResultDTO trashComic(Long comicId, String idempotencyKey);
    OperationSubmitResultDTO trashChapter(Long comicId, Long chapterId);
    OperationSubmitResultDTO trashMedia(Long mediaId);
    OperationSubmitResultDTO restoreComic(Long comicId);
    OperationSubmitResultDTO restoreChapter(Long comicId, Long chapterId);
    OperationSubmitResultDTO restoreMedia(Long mediaId);
    OperationSubmitResultDTO purgeComic(Long comicId, String token);
    OperationSubmitResultDTO purgeChapter(Long comicId, Long chapterId, String token);
    OperationSubmitResultDTO purgeMedia(Long mediaId, String token);
    TrashReconcileReport reconcile(String targetType, Long targetId);
    TrashReconcileReport reconcileAndRepair(String targetType, Long targetId);
}
