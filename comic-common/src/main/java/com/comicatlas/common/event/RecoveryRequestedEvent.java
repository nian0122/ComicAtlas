package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 数据恢复请求，由 API 创建恢复任务后发布，触发 Worker 扫描 HQ 存储并回传可恢复的漫画 ID。
 * 该事件启动恢复流程，不直接写入业务表；恢复结果由后续进度、完成或失败事件驱动。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 恢复任务 ID
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class RecoveryRequestedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId;
    @JsonCreator public RecoveryRequestedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("taskId") Long taskId) { this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; }
    public UUID eventId(){return eventId; } public Instant occurredAt(){return occurredAt; } public Long taskId(){return taskId; }
}
