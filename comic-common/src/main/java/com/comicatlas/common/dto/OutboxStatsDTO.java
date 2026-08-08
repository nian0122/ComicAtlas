package com.comicatlas.common.dto;

/**
 * Outbox 统计信息 — 管理 API 返回。
 */
public record OutboxStatsDTO(
    long pending,
    long failed,
    long total
) {
    public static OutboxStatsDTO of(long pending, long failed, long total) {
        return new OutboxStatsDTO(pending, failed, total);
    }
}
