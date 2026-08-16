package com.comicatlas.api.management.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.contract.common.Result;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 统一管理任务 API。
 * <p>
 * 提供任务 CRUD、cancel、retry、逐目标项查询。
 * 异步命令需携带 Idempotency-Key 头。
 */
@RestController
@RequestMapping("/api/manage/tasks")
@RequiredArgsConstructor
public class ManagementTaskController {

    private final ManagementTaskService managementTaskService;

    /**
     * 分页查询任务列表。
     * 支持 type/status/batchId/targetType/targetId 过滤。
     */
    @GetMapping
    public Result<IPage<ManagementTaskResponse>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TaskType type,
            @RequestParam(required = false) ManagementTaskStatus status,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId) {
        return Result.ok(managementTaskService.listTasks(page, size, type, status, batchId,
                targetType, targetId));
    }

    /**
     * 获取任务详情。
     */
    @GetMapping("/{id}")
    public Result<ManagementTaskResponse> getTask(@PathVariable Long id) {
        return Result.ok(managementTaskService.getTask(id));
    }

    /**
     * 获取任务的逐目标项列表。
     */
    @GetMapping("/{id}/items")
    public Result<List<ManagementTaskItemResponse>> getTaskItems(@PathVariable Long id) {
        return Result.ok(managementTaskService.getTaskItems(id));
    }

    /**
     * 创建管理任务（异步命令）。
     * 需携带 Idempotency-Key 头。
     */
    @PostMapping
    public Result<ManagementTaskResponse> createTask(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateManagementTaskRequest request) {
        // payload 使用 request 的 JSON 表示（由 Spring 自动序列化后的请求体）
        // 简化实现：使用 toString 作为 payload hash 的输入
        String payload = request.toString();
        return Result.ok(managementTaskService.createTask(request, idempotencyKey, payload));
    }

    /**
     * 取消任务。
     */
    @PostMapping("/{id}/cancel")
    public Result<ManagementTaskResponse> cancelTask(@PathVariable Long id) {
        return Result.ok(managementTaskService.cancelTask(id));
    }

    /**
     * 重试任务。
     * 仅终态可重试，保持 taskId/itemId，递增 attempt。
     */
    @PostMapping("/{id}/retry")
    public Result<ManagementTaskResponse> retryTask(@PathVariable Long id) {
        return Result.ok(managementTaskService.retryTask(id));
    }
}
