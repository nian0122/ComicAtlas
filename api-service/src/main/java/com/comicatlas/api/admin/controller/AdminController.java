package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.admin.service.AdminService;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/storage/stats")
    public Result<StorageStatsDTO> storageStats() {
        return Result.ok(adminService.getStorageStats());
    }

    /**
     * 恢复被删除的漫画：扫描 HQ 目录，用 metadata.json 重建 DB 记录。
     */
    @PostMapping("/storage/scan-recover")
    public Result<ScanRecoverResultDTO> scanRecover() {
        return Result.ok(adminService.scanRecover());
    }

    /**
     * 删除漫画。兼容旧 mode 参数（DATABASE_ONLY/DELETE_FILES 均视为回收重定向），
     * 普通入口不再绕过回收站；永久清理走 POST /api/trash/comics/{id}/purge
     * （只接受 TRASHED + 二次确认 token + 7 天保留期）。
     */
    @DeleteMapping("/comics/{id}")
    public Result<ComicDeleteStats> deleteComic(@PathVariable Long id, @RequestParam String mode) {
        return Result.ok(adminService.deleteComic(id, mode));
    }

    /**
     * 刷新单漫画元数据：重新扫描 HQ 目录，更新 page 的宽高/文件大小，
     * 完成后发 MQ 委托 Worker 重新导出 metadata.json。
     *
     * @deprecated 请改用 POST /api/storage/refresh-metadata/comics/{id}
     */
    @Deprecated
    @PostMapping("/comics/{comicId}/refresh-metadata")
    public Result<RefreshMetadataResult> refreshMetadata(@PathVariable Long comicId) {
        return Result.ok(adminService.refreshMetadata(comicId));
    }
}
