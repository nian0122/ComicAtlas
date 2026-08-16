package com.comicatlas.api.upload.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.upload.MediaManagementService;
import com.comicatlas.api.upload.dto.MediaReorderRequest;
import com.comicatlas.api.upload.dto.MediaReorderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体管理端点 — 章节内重排与回收站删除。
 */
@RestController
@RequiredArgsConstructor
public class MediaManagementController {

    private final MediaManagementService mediaManagementService;

    /**
     * 章节内媒体重排。
     * <p>
     * 仅调整 pageNumber 顺序，两阶段更新避免唯一键瞬时冲突；
     * 提交列表必须与章节现有媒体完全一致，否则返回业务错误。
     *
     * @param chapterId 章节 ID
     * @param request   重排请求（媒体 ID 新顺序列表）
     * @return 重排结果（每个媒体新的 pageNumber）
     */
    @PostMapping("/api/manage/chapters/{chapterId}/media/reorder")
    public Result<MediaReorderResponse> reorder(
            @PathVariable Long chapterId,
            @Valid @RequestBody MediaReorderRequest request) {
        return Result.ok(mediaManagementService.reorder(chapterId, request));
    }

    /**
     * 将媒体移入回收站。
     * <p>
     * 走 MEDIA_TRASH 回收管线（先写清单，Worker 移入 TRASH），非物理删除；
     * 返回的管理任务可异步跟踪进度。
     *
     * @param mediaId 媒体 ID
     * @return 管理任务提交结果（任务 ID）
     */
    @DeleteMapping("/api/manage/media/{mediaId}")
    public Result<OperationSubmitResultDTO> trash(@PathVariable Long mediaId) {
        return Result.ok(mediaManagementService.trash(mediaId));
    }
}
