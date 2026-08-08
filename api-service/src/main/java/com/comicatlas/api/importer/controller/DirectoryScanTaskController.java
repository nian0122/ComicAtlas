package com.comicatlas.api.importer.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.dto.DirectoryScanRequest;
import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.api.importer.service.DirectoryScanTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 目录扫描任务接口（导入域）。
 * <p>
 * 基路径 {@code /api/tasks/directory-scan}。扫描指定父目录下的漫画候选目录，
 * 生成可导入来源清单供用户确认后发起导入，扫描过程异步执行。
 */
@RestController
@RequestMapping("/api/tasks/directory-scan")
@RequiredArgsConstructor
public class DirectoryScanTaskController {

    private final DirectoryScanTaskService directoryScanTaskService;

    /**
     * 创建目录扫描任务（异步执行）。
     * <p>
     * 仅传入 parentPath，由 Worker 递归扫描其下的候选漫画目录并汇总结果。
     *
     * @param request 扫描请求（parentPath 待扫描的父目录路径）
     * @return 已创建的扫描任务信息
     */
    @PostMapping
    public Result<DirectoryScanTaskVO> createScanTask(@RequestBody DirectoryScanRequest request) {
        return Result.ok(directoryScanTaskService.createScanTask(request.getParentPath()));
    }

    /**
     * 查询目录扫描任务详情。
     *
     * @param id 任务 ID
     * @return 任务详情（含状态与扫描结果清单）
     */
    @GetMapping("/{id}")
    public Result<DirectoryScanTaskVO> getScanTask(@PathVariable Long id) {
        return Result.ok(directoryScanTaskService.getTaskDetail(id));
    }
}
