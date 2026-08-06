package com.comicatlas.api.importer.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.storage.service.LqOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旧 LQ 生成端点（前端迁移后移除）。
 * <p>
 * 已迁移到 {@link com.comicatlas.api.storage.controller.StorageOperationController} 的
 * POST /api/storage/lq/comics/{id} 与 POST /api/storage/lq/chapters/{id}。
 * 本类仅保留旧路径以兼容未迁移的前端，前端迁移完成后删除。
 *
 * @deprecated 前端迁移后移除，统一走 /api/storage 端点
 */
@Deprecated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LqController {

    private final LqOperationService lqOperationService;

    @Deprecated
    @PostMapping("/comics/{comicId}/lq")
    public Result<OperationSubmitResult> generateComicLq(
            @PathVariable Long comicId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForComic(comicId, regenerate));
    }

    @Deprecated
    @PostMapping("/chapters/{chapterId}/lq")
    public Result<OperationSubmitResult> generateChapterLq(
            @PathVariable Long chapterId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForChapter(chapterId, regenerate));
    }
}
