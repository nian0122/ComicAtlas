package com.comicatlas.common.dto;

/**
 * Outbox 统计信息 — 管理 API 返回。
 */
public record OutboxStats(
    long pending,
    long failed,
    long total
) {
    public static OutboxStats of(long pending, long failed, long total) {
        return new OutboxStats(pending, failed, total);
    }
}
