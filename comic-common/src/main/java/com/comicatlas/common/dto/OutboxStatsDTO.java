package com.comicatlas.common.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Outbox 统计信息 — 管理 API 返回。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class OutboxStatsDTO {
    private final long pending;
    private final long failed;
    private final long total;

    public OutboxStatsDTO(long pending, long failed, long total) {
        this.pending = pending;
        this.failed = failed;
        this.total = total;
    }

    public long pending() { return pending; }
    public long failed() { return failed; }
    public long total() { return total; }

    public static OutboxStatsDTO of(long pending, long failed, long total) {
        return new OutboxStatsDTO(pending, failed, total);
    }
}
