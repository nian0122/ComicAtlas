package com.comicatlas.api.config;

import com.comicatlas.api.task.service.RabbitManagementClient;
import com.comicatlas.api.task.service.RabbitManagementClient.QueueSnapshot;
import com.comicatlas.common.constant.MqQueues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MQ 拓扑启动对账。
 * <p>
 * 应用就绪后枚举 Broker 全部队列，与 {@link MqQueues} 契约全集对比：
 * <ul>
 *   <li>僵尸队列（Broker 残留但契约已移除/改名）→ WARN，提示人工清理；</li>
 *   <li>缺失队列（契约声明但 Broker 未声明）→ WARN，提示声明失败风险。</li>
 * </ul>
 * 防止队列改名或链路移除后旧 durable 实体在 Broker 上无消费者堆积（如 video.transcode.result.queue 事件）。
 * 仅告警不自动删除，避免误删仍在使用的实体。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqTopologyAuditor {

    private static final String RESIDUE_HINT =
            "疑似队列改名或链路移除后的 Broker 残留，Spring 不会自动删除，请人工确认后清理";

    private final RabbitManagementClient managementClient;

    /** 启动对账结果。 */
    public record AuditResult(List<QueueSnapshot> zombieQueues, Set<String> missingQueues) {
        public boolean healthy() {
            return zombieQueues.isEmpty() && missingQueues.isEmpty();
        }
    }

    /**
     * 应用就绪后执行一次拓扑对账。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void auditOnReady() {
        AuditResult result;
        try {
            result = audit();
        } catch (Exception e) {
            log.warn("MQ 拓扑对账跳过: Management API 不可用, error={}", e.getMessage());
            return;
        }
        result.zombieQueues().forEach(snapshot -> log.warn(
                "MQ 拓扑对账: 发现契约外僵尸队列 queue={}, messages={}, consumers={}, {}",
                snapshot.name(), snapshot.messages(), snapshot.consumers(), RESIDUE_HINT));
        result.missingQueues().forEach(queue -> log.warn(
                "MQ 拓扑对账: 契约声明的队列未在 Broker 上存在 queue={}, 请检查声明是否失败",
                queue));
        if (result.healthy()) {
            log.info("MQ 拓扑对账: Broker 队列与契约一致，共 {} 个队列", MqQueues.all().size());
        } else {
            log.warn("MQ 拓扑对账: 发现 {} 个僵尸队列、{} 个缺失队列，请按上述告警处理",
                    result.zombieQueues().size(), result.missingQueues().size());
        }
    }

    /**
     * 执行对账（供启动监听与单元测试复用）。
     */
    public AuditResult audit() {
        Set<String> declared = MqQueues.all();
        List<QueueSnapshot> snapshots = managementClient.listQueues();
        Set<String> onBroker = snapshots.stream().map(QueueSnapshot::name).collect(Collectors.toSet());
        List<QueueSnapshot> zombie = snapshots.stream()
                .filter(snapshot -> !declared.contains(snapshot.name()))
                .sorted(Comparator.comparingLong(QueueSnapshot::messages).reversed())
                .toList();
        Set<String> missing = declared.stream()
                .filter(queue -> !onBroker.contains(queue))
                .collect(Collectors.toUnmodifiableSet());
        return new AuditResult(zombie, missing);
    }
}
