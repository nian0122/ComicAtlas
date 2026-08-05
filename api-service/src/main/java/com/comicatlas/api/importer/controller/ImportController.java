package com.comicatlas.api.importer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.service.ImportService;
import lombok.RequiredArgsConstructor;
import com.comicatlas.api.importer.dto.BatchImportRequest;
import com.comicatlas.api.importer.dto.BatchImportResultVO;
import com.comicatlas.api.importer.dto.ImportRequest;
import com.comicatlas.api.importer.dto.ImportStatusVO;
import com.comicatlas.api.importer.dto.ImportTaskVO;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/tasks/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping
    public Result<ImportTaskVO> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ImportRequest request) {
        return Result.ok(importService.createImportTask(request, idempotencyKey));
    }

    @GetMapping
    public Result<IPage<ImportTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String batchId) {
        return Result.ok(importService.listTasks(page, size, status, batchId));
    }

    @PostMapping("/batch")
    public Result<BatchImportResultVO> createBatch(@RequestBody BatchImportRequest request) {
        return Result.ok(importService.createBatchImportTasks(request));
    }

    @GetMapping("/{id}")
    public Result<ImportTaskVO> getTask(@PathVariable Long id) {
        return Result.ok(importService.getTaskDetail(id));
    }

    @GetMapping("/{id}/status")
    public Result<ImportStatusVO> getTaskStatus(@PathVariable Long id) {
        return Result.ok(importService.getTaskStatus(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<?> cancelTask(@PathVariable Long id) {
        importService.cancelTask(id);
        return Result.ok();
    }

    @PostMapping("/{id}/retry")
    public Result<?> retryTask(@PathVariable Long id) {
        importService.retryTask(id);
        return Result.ok();
    }
}
