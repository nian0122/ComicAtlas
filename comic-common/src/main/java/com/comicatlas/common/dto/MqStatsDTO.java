package com.comicatlas.common.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * MQ 积压与死信统计 — 管理 API 返回。
 * <p>
 * 通过 RabbitMQ Management API 枚举全部队列（含代码已不再声明但残留于 Broker 的僵尸队列），
 * 按队列名后缀 {@code .dlq} 区分死信队列与主队列：
 * <ul>
 *   <li>{@code dlqTotal}：死信队列消息总数（消费失败待处理）；</li>
 *   <li>{@code queuedTotal}：主队列 ready 消息总数（发布成功但未被消费的堆积）；</li>
 *   <li>{@code queues}：有积压的队列明细（死信按总量、主队列按 ready 量，按消息数降序）。</li>
 * </ul>
 * 与 {@link com.comicatlas.common.dto.OutboxStatsDTO}（发布层）互补：本统计覆盖消费层失败与堆积。
 *
 * @param available Management API 是否可用（不可用时前端降级展示）
 * @param dlqTotal 死信队列消息总数
 * @param dlqQueues 有消息的死信队列数
 * @param queuedTotal 主队列 ready 消息总数
 * @param queues 有积压的队列明细
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MqStatsDTO {
    private final boolean available;
    private final long dlqTotal;
    private final int dlqQueues;
    private final long queuedTotal;
    private final List<MqQueueStat> queues;

    public MqStatsDTO(boolean available, long dlqTotal, int dlqQueues, long queuedTotal,
                      List<MqQueueStat> queues) {
        this.available = available;
        this.dlqTotal = dlqTotal;
        this.dlqQueues = dlqQueues;
        this.queuedTotal = queuedTotal;
        this.queues = queues;
    }

    public boolean available() { return available; }
    public long dlqTotal() { return dlqTotal; }
    public int dlqQueues() { return dlqQueues; }
    public long queuedTotal() { return queuedTotal; }
    public List<MqQueueStat> queues() { return queues; }
    /**
     * 单队列积压快照。
     *
     * @param name 队列名
     * @param messages 消息数（死信队列为总量，主队列为 ready 量）
     * @param consumers 当前消费者数（0 表示无消费者，消息可能永久堆积）
     * @param dlq 是否死信队列
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MqQueueStat {
        private final String name;
        private final long messages;
        private final long consumers;
        private final boolean dlq;

        public MqQueueStat(String name, long messages, long consumers, boolean dlq) {
            this.name = name;
            this.messages = messages;
            this.consumers = consumers;
            this.dlq = dlq;
        }

        public String name() { return name; }
        public long messages() { return messages; }
        public long consumers() { return consumers; }
        public boolean dlq() { return dlq; }
    }

    /** Management API 不可用时返回的降级统计。 */
    public static MqStatsDTO unavailable() {
        return new MqStatsDTO(false, 0, 0, 0, List.of());
    }
}
