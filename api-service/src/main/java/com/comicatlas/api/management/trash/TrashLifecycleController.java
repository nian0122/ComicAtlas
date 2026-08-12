package com.comicatlas.api.management.trash;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站生命周期端点 — 恢复 / 永久清理 / 对账。
 * <p>
 * 回收入口沿用既有端点：DELETE /api/comics/{id}、DELETE /api/comics/{comicId}/chapters/{chapterId}、
 * DELETE /api/media/{mediaId}。永久清理只接受 TRASHED + 二次确认 token + 7 天保留期。
 */
@RestController
@RequestMapping("/api/manage/trash")
@RequiredArgsConstructor
public class TrashLifecycleController {

    private final TrashLifecycleService trashLifecycleService;

    // ======================== 恢复 ========================

    /**
     * 从回收站恢复整本漫画（异步命令）。
     * 恢复依赖已存在的回收清单，仅 TRASHED 状态可恢复。
     *
     * @param comicId 漫画 ID
     * @return 操作提交结果
     */
    @PostMapping("/comics/{comicId}/restore")
    public Result<OperationSubmitResultDTO> restoreComic(@PathVariable Long comicId) {
        return Result.ok(trashLifecycleService.restoreComic(comicId));
    }

    /**
     * 从回收站恢复指定漫画下的单个章节（异步命令）。
     *
     * @param comicId 漫画 ID
     * @param chapterId 章节 ID
     * @return 操作提交结果
     */
    @PostMapping("/comics/{comicId}/chapters/{chapterId}/restore")
    public Result<OperationSubmitResultDTO> restoreChapter(@PathVariable Long comicId,
                                                        @PathVariable Long chapterId) {
        return Result.ok(trashLifecycleService.restoreChapter(comicId, chapterId));
    }

    /**
     * 从回收站恢复单个媒体（异步命令）。
     *
     * @param mediaId 媒体 ID
     * @return 操作提交结果
     */
    @PostMapping("/media/{mediaId}/restore")
    public Result<OperationSubmitResultDTO> restoreMedia(@PathVariable Long mediaId) {
        return Result.ok(trashLifecycleService.restoreMedia(mediaId));
    }

    // ======================== 永久清理 ========================

    /**
     * 永久清理整本漫画（不可恢复）。
     * 仅接受 TRASHED 状态 + 二次确认 token + 7 天保留期已过。
     *
     * @param comicId 漫画 ID
     * @param request 二次确认 token
     * @return 操作提交结果
     */
    @PostMapping("/comics/{comicId}/purge")
    public Result<OperationSubmitResultDTO> purgeComic(@PathVariable Long comicId,
                                                    @Valid @RequestBody PurgeRequest request) {
        return Result.ok(trashLifecycleService.purgeComic(comicId, request.getToken()));
    }

    /**
     * 永久清理指定漫画下的单个章节（不可恢复）。
     * 仅接受 TRASHED 状态 + 二次确认 token + 7 天保留期已过。
     *
     * @param comicId 漫画 ID
     * @param chapterId 章节 ID
     * @param request 二次确认 token
     * @return 操作提交结果
     */
    @PostMapping("/comics/{comicId}/chapters/{chapterId}/purge")
    public Result<OperationSubmitResultDTO> purgeChapter(@PathVariable Long comicId,
                                                      @PathVariable Long chapterId,
                                                      @Valid @RequestBody PurgeRequest request) {
        return Result.ok(trashLifecycleService.purgeChapter(comicId, chapterId, request.getToken()));
    }

    /**
     * 永久清理单个媒体（不可恢复）。
     * 仅接受 TRASHED 状态 + 二次确认 token + 7 天保留期已过。
     *
     * @param mediaId 媒体 ID
     * @param request 二次确认 token
     * @return 操作提交结果
     */
    @PostMapping("/media/{mediaId}/purge")
    public Result<OperationSubmitResultDTO> purgeMedia(@PathVariable Long mediaId,
                                                    @Valid @RequestBody PurgeRequest request) {
        return Result.ok(trashLifecycleService.purgeMedia(mediaId, request.getToken()));
    }

    // ======================== 对账 ========================

    /**
     * 对账指定目标的回收状态（只读）：对比 DB 状态、回收清单与实际文件，输出差异报告。
     *
     * @param targetType 目标类型（COMIC/CHAPTER/MEDIA）
     * @param targetId 目标 ID
     * @return 对账报告
     */
    @GetMapping("/{targetType}/{targetId}/reconcile")
    public Result<TrashReconcileReport> reconcile(@PathVariable String targetType,
                                                  @PathVariable Long targetId) {
        return Result.ok(trashLifecycleService.reconcile(targetType, targetId));
    }

    /** 对账并修复可安全自动恢复的 DB 状态。 */
    @PostMapping("/{targetType}/{targetId}/reconcile")
    public Result<TrashReconcileReport> reconcileAndRepair(@PathVariable String targetType,
                                                           @PathVariable Long targetId) {
        return Result.ok(trashLifecycleService.reconcileAndRepair(targetType, targetId));
    }
}
