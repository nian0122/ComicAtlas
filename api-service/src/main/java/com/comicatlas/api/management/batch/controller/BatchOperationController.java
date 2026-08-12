package com.comicatlas.api.management.batch.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.management.batch.dto.BatchCreateResponse;
import com.comicatlas.api.management.batch.dto.BatchOperationRequest;
import com.comicatlas.api.management.batch.dto.BatchPreviewResponse;
import com.comicatlas.api.management.batch.service.BatchOperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 跨页批量操作 API。
 * <p>
 * preview 解析选择并返回 selectedCount/eligibleCount/blocked；
 * 创建批量任务（危险操作需携带 preview token 二次确认）。
 */
@RestController
@RequestMapping("/api/manage/batch")
@RequiredArgsConstructor
public class BatchOperationController {

    private final BatchOperationService batchOperationService;

    /** 批量选择预览：命中/可执行/被阻止数量 + 危险操作 preview token */
    @PostMapping("/preview")
    public Result<BatchPreviewResponse> preview(@Valid @RequestBody BatchOperationRequest request) {
        return Result.ok(batchOperationService.preview(request));
    }

    /** 创建批量任务（物化 items 快照）；危险操作需 previewToken */
    @PostMapping
    public Result<BatchCreateResponse> createBatch(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchOperationRequest request) {
        return Result.ok(batchOperationService.createBatch(request, idempotencyKey));
    }
}
