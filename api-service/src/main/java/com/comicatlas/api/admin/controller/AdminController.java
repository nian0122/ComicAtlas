package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.admin.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/storage/stats")
    public Result<StorageStatsDTO> storageStats() {
        return Result.ok(adminService.getStorageStats());
    }

    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuildFromHq() {
        return Result.ok(adminService.rebuildFromHq());
    }

    @PostMapping("/storage/scan-recover")
    public Result<ScanRecoverResultDTO> scanRecover() {
        return Result.ok(adminService.scanRecover());
    }

    @DeleteMapping("/comics/{id}")
    public Result<ComicDeleteStats> deleteComic(@PathVariable Long id, @RequestParam String mode) {
        return Result.ok(adminService.deleteComic(id, mode));
    }
}
