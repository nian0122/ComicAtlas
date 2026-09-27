package com.comicatlas.api.media.controller;

import com.comicatlas.api.media.dto.ContentReactionBatchRequest;
import com.comicatlas.api.media.dto.ContentReactionVO;
import com.comicatlas.api.media.service.ContentReactionManagementService;
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

/** 管理端漫画和章节标记查询与批量操作。 */
@RestController
@RequestMapping("/api/manage/content/reactions")
@RequiredArgsConstructor
public class ContentReactionManagementController {
    private final ContentReactionManagementService service;

    @GetMapping
    public Result<List<ContentReactionVO>> list(
            @RequestParam String targetType,
            @RequestParam(required = false) MediaReaction reaction,
            @RequestParam(defaultValue = "false") boolean includeTrashed) {
        return Result.ok(service.list(targetType, reaction, includeTrashed));
    }

    @PutMapping("/batch")
    public Result<Integer> updateBatch(@RequestParam String targetType,
                                       @Valid @RequestBody ContentReactionBatchRequest request) {
        return Result.ok(service.updateBatch(targetType, request));
    }

    @PostMapping("/batch/trash")
    public Result<List<OperationSubmitResultDTO>> trashBatch(
            @RequestParam String targetType, @Valid @RequestBody ContentReactionBatchRequest request) {
        return Result.ok(service.trashBatch(targetType, request.getIds()));
    }
}
