package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导出任务失败事件，由 Worker 无法生成导出产物时发布，驱动 API 将任务置为失败并保存错误信息。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 导出的漫画 ID
 * @param errorCode 稳定的错误码
 * @param errorMessage 导出失败原因
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ExportTaskFailedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final Long comicId;
    private final String errorCode; private final String errorMessage;
    @JsonCreator
    public ExportTaskFailedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                 @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId,
                                 @JsonProperty("errorCode") String errorCode, @JsonProperty("errorMessage") String errorMessage) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId; this.errorCode=errorCode; this.errorMessage=errorMessage;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public Long comicId(){return comicId;}
    public String errorCode(){return errorCode;} public String errorMessage(){return errorMessage;}
}
