package com.comicatlas.api.dashboard.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/statistics")
    public Result<?> getStatistics() {
        return Result.ok(dashboardService.getStatistics());
    }
}
