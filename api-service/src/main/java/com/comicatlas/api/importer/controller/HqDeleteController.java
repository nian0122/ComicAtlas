package com.comicatlas.api.importer.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 旧 HQ 删除端点（前端迁移后移除）。
 * <p>
 * 已迁移到 {@link com.comicatlas.api.storage.controller.StorageOperationController} 的
 * POST /api/storage/delete-hq/comics/{id} 与 POST /api/storage/delete-hq/chapters/{id}。
 * 本类仅保留旧路径以兼容未迁移的前端，前端迁移完成后删除。
 *
 * @deprecated 前端迁移后移除，统一走 /api/storage 端点
 */
@Slf4j
@Deprecated
@RestController
@RequiredArgsConstructor
public class HqDeleteController {
    private final HqDeleteOperationService hqDeleteOperationService;

    @Deprecated
    @PostMapping("/api/comics/{comicId}/delete-hq")
    public Result<OperationSubmitResult> deleteComicHq(@PathVariable Long comicId) {
        log.info("请求删除漫画 HQ: comicId={}", comicId);
        return Result.ok(hqDeleteOperationService.deleteForComic(comicId));
    }

    @Deprecated
    @PostMapping("/api/chapters/{chapterId}/delete-hq")
    public Result<OperationSubmitResult> deleteChapterHq(@PathVariable Long chapterId) {
        log.info("请求删除章节 HQ: chapterId={}", chapterId);
        return Result.ok(hqDeleteOperationService.deleteForChapter(chapterId));
    }
}
