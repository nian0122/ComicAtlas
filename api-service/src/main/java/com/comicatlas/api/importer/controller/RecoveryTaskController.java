package com.comicatlas.api.importer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.dto.RecoveryTaskVO;
import com.comicatlas.api.importer.service.RecoveryTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/tasks/recovery")
@RequiredArgsConstructor
public class RecoveryTaskController {

    private final RecoveryTaskService recoveryTaskService;

    @PostMapping
    public Result<RecoveryTaskVO> createTask() {
        return Result.ok(recoveryTaskService.createRecoveryTask());
    }

    @GetMapping
    public Result<IPage<RecoveryTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(recoveryTaskService.listTasks(page, size));
    }

    @GetMapping("/{id}")
    public Result<RecoveryTaskVO> getTask(@PathVariable Long id) {
        return Result.ok(recoveryTaskService.getTaskDetail(id));
    }

    @PostMapping("/{id}/retry")
    public Result<RecoveryTaskVO> retryTask(@PathVariable Long id) {
        return Result.ok(recoveryTaskService.retryTask(id));
    }
}
