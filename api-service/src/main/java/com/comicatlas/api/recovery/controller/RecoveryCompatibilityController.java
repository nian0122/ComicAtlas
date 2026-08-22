package com.comicatlas.api.recovery.controller;

import com.comicatlas.api.recovery.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.dto.ScanRecoverResultDTO;
import com.comicatlas.contract.common.Result;
import com.comicatlas.api.recovery.service.RecoveryCompatibilityService;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 管理端兼容接口（管理域）。
 * <p>
 * 基路径 {@code /api/admin}，保留扫描恢复与旧版删除入口。
 * 普通删除已统一走回收站，本控制器仅供本机管理端使用。
 */
@RestController
@RequestMapping("/api/manage/admin")
@RequiredArgsConstructor
public class RecoveryCompatibilityController {

    private final RecoveryCompatibilityService adminService;

    /**
     * 恢复被删除的漫画：扫描 HQ 目录，用 metadata.json 重建 DB 记录。
     * 兼容性保留端点，新增恢复能力优先走恢复任务中心。
     *
     * @return 扫描恢复结果
     */
    @PostMapping("/storage/scan-recover")
    public Result<ScanRecoverResultDTO> scanRecover() {
        return Result.ok(adminService.scanRecover());
    }

    /**
     * 删除漫画。兼容旧 mode 参数（DATABASE_ONLY/DELETE_FILES 均视为回收重定向），
     * 普通入口不再绕过回收站；永久清理走 POST /api/trash/comics/{id}/purge
     * （只接受 TRASHED + 二次确认 token + 7 天保留期）。
     *
     * @param id 漫画 ID
     * @param mode 旧模式参数（仅兼容，不改变删除语义）
     * @return 删除统计
     */
    @DeleteMapping("/comics/{id}")
    public Result<ComicDeleteStatsDTO> deleteComic(@PathVariable Long id, @RequestParam String mode) {
        return Result.ok(adminService.deleteComic(id, mode));
    }
}
