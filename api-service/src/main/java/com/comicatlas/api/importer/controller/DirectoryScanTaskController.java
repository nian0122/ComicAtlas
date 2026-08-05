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

@RestController
@RequestMapping("/api/tasks/directory-scan")
@RequiredArgsConstructor
public class DirectoryScanTaskController {

    private final DirectoryScanTaskService directoryScanTaskService;

    @PostMapping
    public Result<DirectoryScanTaskVO> createScanTask(@RequestBody DirectoryScanRequest request) {
        return Result.ok(directoryScanTaskService.createScanTask(request.getParentPath()));
    }

    @GetMapping("/{id}")
    public Result<DirectoryScanTaskVO> getScanTask(@PathVariable Long id) {
        return Result.ok(directoryScanTaskService.getTaskDetail(id));
    }
}
