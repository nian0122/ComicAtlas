package com.comicatlas.api.storage.controller;

import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储统计（存储操作域）。
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageStatsController {

    private final AdminService adminService;

    @GetMapping("/stats")
    public Result<StorageStatsDTO> stats() {
        return Result.ok(adminService.getStorageStats());
    }
}
