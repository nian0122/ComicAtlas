package com.comicatlas.api.management.service;

import com.comicatlas.api.admin.service.RabbitManagementClient;
import com.comicatlas.api.admin.service.RabbitManagementClient.QueueSnapshot;
import com.comicatlas.common.dto.MqStatsDTO;
import com.comicatlas.common.dto.MqStatsDTO.MqQueueStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MQ 积压与死信统计服务。
 * <p>
 * 枚举 Broker 全部队列，按 {@code .dlq} 后缀区分死信队列与主队列：
 * 死信统计总量（消费失败），主队列统计 ready 量（发布成功未消费的堆积）。
 * Management API 不可用时返回 {@link MqStatsDTO#unavailable()} 降级，不影响管理页主体。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqStatsService {

    private static final String DLQ_SUFFIX = ".dlq";

    private final RabbitManagementClient managementClient;

    public MqStatsDTO stats() {
        try {
            List<QueueSnapshot> snapshots = managementClient.listQueues();
            long dlqTotal = 0;
            int dlqQueues = 0;
            long queuedTotal = 0;
            List<MqQueueStat> busyQueues = new ArrayList<>();
            for (QueueSnapshot snapshot : snapshots) {
                boolean dlq = snapshot.name().endsWith(DLQ_SUFFIX);
                if (dlq) {
                    if (snapshot.messages() > 0) {
                        dlqTotal += snapshot.messages();
                        dlqQueues++;
                        busyQueues.add(new MqQueueStat(snapshot.name(), snapshot.messages(), snapshot.consumers(), true));
                    }
                } else if (snapshot.messagesReady() > 0) {
                    queuedTotal += snapshot.messagesReady();
                    busyQueues.add(new MqQueueStat(snapshot.name(), snapshot.messagesReady(), snapshot.consumers(), false));
                }
            }
            busyQueues.sort(Comparator.comparingLong(MqQueueStat::messages).reversed());
            return new MqStatsDTO(true, dlqTotal, dlqQueues, queuedTotal, List.copyOf(busyQueues));
        } catch (Exception e) {
            log.warn("MQ 积压统计不可用（Management API 异常）: {}", e.getMessage());
            return MqStatsDTO.unavailable();
        }
    }
}
