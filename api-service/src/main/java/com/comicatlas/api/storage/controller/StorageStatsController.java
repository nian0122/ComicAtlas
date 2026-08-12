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
 * <p>
 * 基路径 {@code /api/storage}，统计漫画库存储总量与 HQ/LQ 状态分布，
 * 供管理端存储管理页展示。仅供本机管理端使用。
 */
@RestController
@RequestMapping("/api/manage/storage")
@RequiredArgsConstructor
public class StorageStatsController {

    private final AdminService adminService;

    /**
     * 查询存储统计汇总。
     *
     * @return 存储统计（总量/各存储根大小与状态分布）
     */
    @GetMapping("/stats")
    public Result<StorageStatsDTO> stats() {
        return Result.ok(adminService.getStorageStats());
    }
}
