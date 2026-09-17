package com.comicatlas.api.outbox.application.port.in;

import com.comicatlas.common.dto.OutboxStatsDTO;

/** Outbox 统计查询服务契约。 */
public interface OutboxStatsService {
    OutboxStatsDTO getStats();
}
