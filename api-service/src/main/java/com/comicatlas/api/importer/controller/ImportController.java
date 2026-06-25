package com.comicatlas.api.importer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.dto.*;
import com.comicatlas.api.importer.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping
    public Result<ImportTaskVO> createTask(@RequestBody ImportRequest request) {
        return Result.ok(importService.createImportTask(request));
    }

    @GetMapping
    public Result<IPage<ImportTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String status) {
        return Result.ok(importService.listTasks(page, size, status));
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
