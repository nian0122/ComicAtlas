package com.comicatlas.api.task.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.outbox.service.OutboxStatsService;
import com.comicatlas.common.dto.OutboxStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbox 统计管理 API。
 * <p>
 * 暴露 outbox 消息积压和失败计数，用于监控和告警。
 */
@RestController
@RequestMapping("/api/manage/outbox")
@RequiredArgsConstructor
public class OutboxStatsController {

    private final OutboxStatsService outboxStatsService;

    /**
     * 获取 Outbox 统计信息。
     */
    @GetMapping("/stats")
    public Result<OutboxStatsDTO> stats() {
        return Result.ok(outboxStatsService.getStats());
    }
}
