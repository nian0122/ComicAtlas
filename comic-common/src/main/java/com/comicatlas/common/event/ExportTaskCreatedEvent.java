package com.comicatlas.common.event;

import com.comicatlas.common.constant.ExportFormats;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导出任务创建事件，由管理端创建导出任务后发布，触发 Worker 收集漫画并生成导出文件。
 * 消费方应将对应导出任务视为已进入异步处理流程；该事件本身不表示导出已完成。
 *
 * @param eventId 事件唯一标识，用于消息追踪和幂等处理
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 待导出的漫画 ID
 * @param format 导出格式，例如 {@code ZIP}
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ExportTaskCreatedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final Long comicId; private final String format;
    @JsonCreator
    public ExportTaskCreatedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                  @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId,
                                  @JsonProperty("format") String format) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId; this.format=format;
    }
    public ExportTaskCreatedEvent(UUID eventId, Instant occurredAt, Long taskId, Long comicId) {
        this(eventId, occurredAt, taskId, comicId, ExportFormats.ZIP);
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;}
    public Long comicId(){return comicId;} public String format(){return format;}
}
