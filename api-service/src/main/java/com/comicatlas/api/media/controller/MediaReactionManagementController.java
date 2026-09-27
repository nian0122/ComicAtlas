package com.comicatlas.api.media.controller;

import com.comicatlas.api.media.dto.MediaBatchTrashRequest;
import com.comicatlas.api.media.dto.MediaReactionBatchRequest;
import com.comicatlas.api.media.dto.MediaReactionVO;
import com.comicatlas.api.media.service.MediaReactionManagementService;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.common.enums.MediaReaction;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端媒体标记查询与批量操作。 */
@RestController
@RequestMapping("/api/manage/media/reactions")
@RequiredArgsConstructor
public class MediaReactionManagementController {
    private final MediaReactionManagementService service;

    @GetMapping
    public Result<List<MediaReactionVO>> list(
            @RequestParam(required = false) MediaReaction reaction,
            @RequestParam(required = false) String mediaType,
            @RequestParam(defaultValue = "false") boolean includeTrashed) {
        return Result.ok(service.list(reaction, mediaType, includeTrashed));
    }

    @PutMapping("/batch")
    public Result<Integer> updateBatch(@Valid @RequestBody MediaReactionBatchRequest request) {
        return Result.ok(service.updateBatch(request));
    }

    @PostMapping("/batch/trash")
    public Result<List<OperationSubmitResultDTO>> trashBatch(@Valid @RequestBody MediaBatchTrashRequest request) {
        return Result.ok(service.trashBatch(request.getMediaIds()));
    }
}
