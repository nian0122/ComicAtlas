package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导出任务开始事件，由 Worker 开始收集或构建导出产物时发布，驱动 API 将任务标记为运行中。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 正在导出的漫画 ID
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ExportTaskStartedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final Long comicId;
    @JsonCreator
    public ExportTaskStartedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                  @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public Long comicId(){return comicId;}
}
