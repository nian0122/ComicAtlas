package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 漫画导入任务创建事件，由 API 预创建漫画和导入任务后发布，触发 Worker 按来源类型执行导入。
 * 发布后任务进入异步处理流程，漫画保持 IMPORTING，直到各章节存储最终化完成才转为 READY。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导入任务 ID
 * @param comicId 预创建的漫画 ID
 * @param sourceType 来源类型，如 ZIP、REGISTER 或 EHENTAI
 * @param sourcePath 来源路径或来源标识
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ImportTaskCreatedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId;
    private final Long comicId; private final String sourceType; private final String sourcePath;
    @JsonCreator
    public ImportTaskCreatedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                  @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId,
                                  @JsonProperty("sourceType") String sourceType, @JsonProperty("sourcePath") String sourcePath) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId;
        this.sourceType=sourceType; this.sourcePath=sourcePath;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;}
    public Long comicId(){return comicId;} public String sourceType(){return sourceType;} public String sourcePath(){return sourcePath;}
}
