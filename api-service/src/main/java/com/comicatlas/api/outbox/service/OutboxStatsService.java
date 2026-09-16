package com.comicatlas.api.outbox.service;

import com.comicatlas.api.outbox.persistence.mapper.OutboxMessageMapper;
import com.comicatlas.common.dto.OutboxStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Outbox 统计查询服务。 */
@Service
@RequiredArgsConstructor
public class OutboxStatsService {
    // TODO(LAYER-14): Service 功能契约与具体实现未分离；应抽取 service 接口，并将实现迁移到 service/impl。

    private final OutboxMessageMapper outboxMessageMapper;

    public OutboxStatsDTO getStats() {
        long pending = outboxMessageMapper.countPending();
        long failed = outboxMessageMapper.countFailed();
        long total = outboxMessageMapper.selectCount(null);
        return OutboxStatsDTO.of(pending, failed, total);
    }
}
