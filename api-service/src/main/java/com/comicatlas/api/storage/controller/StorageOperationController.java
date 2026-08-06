package com.comicatlas.api.storage.controller;

import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import com.comicatlas.api.storage.service.LqOperationService;
import com.comicatlas.api.storage.service.MetadataRefreshService;
import com.comicatlas.api.storage.service.TranscodeOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储操作统一入口（存储操作域）。
 * <p>
 * URL 形态：POST /api/storage/{operation}/{targetType}/{targetId}，targetType = comics | chapters。
 * 后续转码 / 导出 / 统计端点追加到本类。
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageOperationController {

    private final LqOperationService lqOperationService;
    private final HqDeleteOperationService hqDeleteOperationService;
    private final TranscodeOperationService transcodeOperationService;
    private final MetadataRefreshService metadataRefreshService;

    // ======================== LQ 生成 ========================

    @PostMapping("/lq/comics/{comicId}")
    public Result<OperationSubmitResult> generateComicLq(
            @PathVariable Long comicId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForComic(comicId, regenerate));
    }

    @PostMapping("/lq/chapters/{chapterId}")
    public Result<OperationSubmitResult> generateChapterLq(
            @PathVariable Long chapterId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForChapter(chapterId, regenerate));
    }

    // ======================== HQ 删除（保留 LQ） ========================

    @PostMapping("/delete-hq/comics/{comicId}")
    public Result<OperationSubmitResult> deleteComicHq(@PathVariable Long comicId) {
        return Result.ok(hqDeleteOperationService.deleteForComic(comicId));
    }

    @PostMapping("/delete-hq/chapters/{chapterId}")
    public Result<OperationSubmitResult> deleteChapterHq(@PathVariable Long chapterId) {
        return Result.ok(hqDeleteOperationService.deleteForChapter(chapterId));
    }

    // ======================== 视频转码 ========================

    @PostMapping("/transcode/comics/{comicId}")
    public Result<OperationSubmitResult> transcodeComic(@PathVariable Long comicId) {
        return Result.ok(transcodeOperationService.transcodeForComic(comicId));
    }

    @PostMapping("/transcode/chapters/{chapterId}")
    public Result<OperationSubmitResult> transcodeChapter(@PathVariable Long chapterId) {
        return Result.ok(transcodeOperationService.transcodeForChapter(chapterId));
    }

    // ======================== 刷新元数据 ========================

    @PostMapping("/refresh-metadata/comics/{comicId}")
    public Result<RefreshMetadataResult> refreshMetadata(@PathVariable Long comicId) {
        return Result.ok(metadataRefreshService.refresh(comicId));
    }
}
