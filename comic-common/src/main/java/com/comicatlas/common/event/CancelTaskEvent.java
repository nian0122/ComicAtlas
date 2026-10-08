package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理任务取消请求，由用户取消任务时发布，通知 Worker 设置取消标记并尽快停止处理。
 * API 侧任务最终状态由后续状态事件或任务处理结果决定，不由本事件直接完成状态落库。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 待取消的任务 ID
 * @param comicId 任务关联的漫画 ID，可为空
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class CancelTaskEvent implements ComicEvent {
    private final UUID eventId;
    private final Instant occurredAt;
    private final Long taskId;
    private final Long comicId;

    @JsonCreator
    public CancelTaskEvent(@JsonProperty("eventId") UUID eventId,
                           @JsonProperty("occurredAt") Instant occurredAt,
                           @JsonProperty("taskId") Long taskId,
                           @JsonProperty("comicId") Long comicId) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.taskId = taskId;
        this.comicId = comicId;
    }

    public UUID eventId() { return eventId; }
    public Instant occurredAt() { return occurredAt; }
    public Long taskId() { return taskId; }
    public Long comicId() { return comicId; }
}
