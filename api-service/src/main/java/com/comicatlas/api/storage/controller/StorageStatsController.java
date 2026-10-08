package com.comicatlas.api.storage.controller;

import com.comicatlas.api.storage.dto.StorageStatsDTO;
import com.comicatlas.api.storage.service.StorageStatisticsService;
import com.comicatlas.api.storage.service.ThumbnailSnapshotService;
import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import com.comicatlas.contract.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储统计（存储操作域）。
 * <p>
 * 基路径 {@code /api/manage/storage}，统计活动媒体登记容量与缩略图目录快照，
 * 供管理端存储管理页展示。仅供本机管理端使用。
 */
@RestController
@RequestMapping("/api/manage/storage")
@RequiredArgsConstructor
public class StorageStatsController {

    private final StorageStatisticsService statisticsService;
    private final ThumbnailSnapshotService thumbnailSnapshotService;

    /**
     * 查询存储统计汇总。
     *
     * @return 存储统计（总量/各存储根大小与状态分布）
     */
    @GetMapping("/stats")
    public Result<StorageStatsDTO> stats() {
        return Result.ok(statisticsService.getStatistics());
    }
    /** 登记后台容量核对请求，不等待扫描结束。 */
    @PostMapping("/stats/refresh")
    public ResponseEntity<Result<RefreshResponse>> refresh() {
        return ResponseEntity.accepted().body(Result.ok(new RefreshResponse(thumbnailSnapshotService.requestRefresh())));
    }

    /** 异步刷新受理结果。 */
    public record RefreshResponse(SnapshotRefreshStatus refreshStatus) { }
}
