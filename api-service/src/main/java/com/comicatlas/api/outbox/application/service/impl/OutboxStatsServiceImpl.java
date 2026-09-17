package com.comicatlas.api.outbox.application.service.impl;

import com.comicatlas.api.outbox.application.port.in.OutboxStatsService;
import com.comicatlas.api.outbox.application.port.out.OutboxPersistencePort;
import com.comicatlas.common.dto.OutboxStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Outbox 统计查询服务。 */
@Service
@RequiredArgsConstructor
public class OutboxStatsServiceImpl implements OutboxStatsService {
    // Outbox 统计契约由应用服务公开，具体实现保持在业务包内。

    private final OutboxPersistencePort outboxPersistencePort;

    public OutboxStatsDTO getStats() {
        long pending = outboxPersistencePort.countPending();
        long failed = outboxPersistencePort.countFailed();
        long total = outboxPersistencePort.countTotal();
        return OutboxStatsDTO.of(pending, failed, total);
    }
}
