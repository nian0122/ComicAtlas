package com.comicatlas.api.upload.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
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

    @PostMapping("/api/chapters/{chapterId}/media/reorder")
    public Result<MediaReorderResponse> reorder(
            @PathVariable Long chapterId,
            @Valid @RequestBody MediaReorderRequest request) {
        return Result.ok(mediaManagementService.reorder(chapterId, request));
    }

    @DeleteMapping("/api/media/{mediaId}")
    public Result<OperationSubmitResult> trash(@PathVariable Long mediaId) {
        return Result.ok(mediaManagementService.trash(mediaId));
    }
}
