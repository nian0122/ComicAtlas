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

/**
 * 导入任务接口（导入域）。
 * <p>
 * 基路径 {@code /api/tasks/import}。接收 ZIP / 本地目录 / EHENTAI 来源的漫画导入，
 * 创建异步导入任务并通过 MQ 下发 Worker 处理；支持任务列表查询、取消与失败重试，
 * 并提供批量导入入口。
 */
@RestController
@RequestMapping("/api/manage/tasks/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    /**
     * 创建单个导入任务（异步执行）。
     * <p>
     * 按 sourceType 区分来源：ZIP 为压缩包路径、DIRECTORY 为本地目录路径、
     * EHENTAI 为画廊 URL。任务创建后立即返回，实际导入由 Worker 异步完成；
     * 携带 Idempotency-Key 时同键同 payload 重复提交幂等返回已有任务。
     *
     * @param idempotencyKey 幂等键（可选），幂等命中时直接返回已有任务
     * @param request        导入来源信息（sourceType + sourcePath/sourceRef）
     * @return 已创建的导入任务信息
     */
    @PostMapping
    public Result<ImportTaskVO> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ImportRequest request) {
        return Result.ok(importService.createImportTask(request, idempotencyKey));
    }

    /**
     * 分页查询导入任务列表。
     *
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @param status  按任务状态过滤（可选）
     * @param batchId 按批次号过滤（可选），用于查看某次批量导入的全部任务
     * @return 导入任务分页结果
     */
    @GetMapping
    public Result<IPage<ImportTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String batchId) {
        return Result.ok(importService.listTasks(page, size, status, batchId));
    }

    /**
     * 批量创建导入任务（同一批次）。
     * <p>
     * 一次请求中的多个来源共用批次号，便于统一跟踪与按 batchId 筛选。
     *
     * @param request 批次导入信息（sourceType + 多个 sourcePaths）
     * @return 批量创建结果（成功/失败明细）
     */
    @PostMapping("/batch")
    public Result<BatchImportResultVO> createBatch(@RequestBody BatchImportRequest request) {
        return Result.ok(importService.createBatchImportTasks(request));
    }

    /**
     * 查询单个导入任务详情。
     *
     * @param id 任务 ID
     * @return 任务详情（含状态、进度、来源与错误信息）
     */
    @GetMapping("/{id}")
    public Result<ImportTaskVO> getTask(@PathVariable Long id) {
        return Result.ok(importService.getTaskDetail(id));
    }

    /**
     * 查询导入任务的实时状态。
     *
     * @param id 任务 ID
     * @return 任务状态与进度
     */
    @GetMapping("/{id}/status")
    public Result<ImportStatusVO> getTaskStatus(@PathVariable Long id) {
        return Result.ok(importService.getTaskStatus(id));
    }

    /**
     * 取消导入任务。
     * <p>
     * 仅对未完成的任务生效，取消信号通过 MQ 下发 Worker 中断处理。
     *
     * @param id 任务 ID
     * @return 空结果
     */
    @PostMapping("/{id}/cancel")
    public Result<?> cancelTask(@PathVariable Long id) {
        importService.cancelTask(id);
        return Result.ok();
    }

    /**
     * 重试失败的导入任务。
     * <p>
     * 复用原来源信息重新入队，任务重新进入执行流程。
     *
     * @param id 任务 ID
     * @return 空结果
     */
    @PostMapping("/{id}/retry")
    public Result<?> retryTask(@PathVariable Long id) {
        importService.retryTask(id);
        return Result.ok();
    }
}
