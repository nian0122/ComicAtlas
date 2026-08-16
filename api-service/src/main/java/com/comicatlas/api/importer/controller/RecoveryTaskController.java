package com.comicatlas.api.importer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.contract.common.Result;
import com.comicatlas.api.importer.dto.RecoveryTaskVO;
import com.comicatlas.api.importer.service.RecoveryTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 存储恢复任务接口（导入域）。
 * <p>
 * 基路径 {@code /api/tasks/recovery}。扫描 HQ 目录中的漫画文件，
 * 从文件重建数据库记录（恢复未登记或丢失的漫画数据），支持任务列表、详情与失败重试。
 */
@RestController
@RequestMapping("/api/manage/tasks/recovery")
@RequiredArgsConstructor
public class RecoveryTaskController {

    private final RecoveryTaskService recoveryTaskService;

    /**
     * 创建存储恢复任务（异步执行）。
     * <p>
     * 无请求参数，触发一次全量扫描：Worker 扫描 HQ 目录并发布发现的 comicId 列表，
     * API 侧逐本从文件重建数据库记录。
     *
     * @return 已创建的恢复任务信息
     */
    @PostMapping
    public Result<RecoveryTaskVO> createTask() {
        return Result.ok(recoveryTaskService.createRecoveryTask());
    }

    /**
     * 分页查询存储恢复任务列表。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 恢复任务分页结果
     */
    @GetMapping
    public Result<IPage<RecoveryTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(recoveryTaskService.listTasks(page, size));
    }

    /**
     * 查询单个恢复任务详情。
     *
     * @param id 任务 ID
     * @return 任务详情（含各分类计数与错误明细）
     */
    @GetMapping("/{id}")
    public Result<RecoveryTaskVO> getTask(@PathVariable Long id) {
        return Result.ok(recoveryTaskService.getTaskDetail(id));
    }

    /**
     * 重试失败的恢复任务。
     * <p>
     * 重新触发对失败漫画的恢复流程。
     *
     * @param id 任务 ID
     * @return 重试后的任务详情
     */
    @PostMapping("/{id}/retry")
    public Result<RecoveryTaskVO> retryTask(@PathVariable Long id) {
        return Result.ok(recoveryTaskService.retryTask(id));
    }
}
