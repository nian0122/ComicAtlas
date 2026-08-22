package com.comicatlas.api.outbox.service;

import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.common.dto.OutboxStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Outbox 统计查询服务。 */
@Service
@RequiredArgsConstructor
public class OutboxStatsService {

    private final OutboxMessageMapper outboxMessageMapper;

    public OutboxStatsDTO getStats() {
        long pending = outboxMessageMapper.countPending();
        long failed = outboxMessageMapper.countFailed();
        long total = outboxMessageMapper.selectCount(null);
        return OutboxStatsDTO.of(pending, failed, total);
    }
}
