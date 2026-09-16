package com.comicatlas.api.outbox.service;

import com.comicatlas.api.outbox.persistence.mapper.OutboxMessageMapper;
import com.comicatlas.common.dto.OutboxStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Outbox 统计查询服务。 */
@Service
@RequiredArgsConstructor
public class OutboxStatsService {
    // Outbox 统计契约由应用服务公开，具体实现保持在业务包内。

    private final OutboxMessageMapper outboxMessageMapper;

    public OutboxStatsDTO getStats() {
        long pending = outboxMessageMapper.countPending();
        long failed = outboxMessageMapper.countFailed();
        long total = outboxMessageMapper.selectCount(null);
        return OutboxStatsDTO.of(pending, failed, total);
    }
}
