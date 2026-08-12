package com.comicatlas.api.management.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
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

    private final OutboxMessageMapper outboxMapper;

    /**
     * 获取 Outbox 统计信息。
     */
    @GetMapping("/stats")
    public Result<OutboxStatsDTO> stats() {
        long pending = outboxMapper.countPending();
        long failed = outboxMapper.countFailed();
        long total = outboxMapper.selectCount(null);
        return Result.ok(OutboxStatsDTO.of(pending, failed, total));
    }
}
