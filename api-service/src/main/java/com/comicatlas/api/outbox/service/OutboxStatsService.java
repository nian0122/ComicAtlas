package com.comicatlas.api.outbox.service;

import com.comicatlas.common.dto.OutboxStatsDTO;

/** Outbox 统计查询服务契约。 */
public interface OutboxStatsService {
    OutboxStatsDTO getStats();
}
