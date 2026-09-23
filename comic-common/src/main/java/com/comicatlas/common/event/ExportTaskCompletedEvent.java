package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导出任务成功事件，由 Worker 原子发布导出产物后发送，驱动 API 将任务置为成功并登记产物信息。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 导出的漫画 ID
 * @param outputRoot 导出产物所在存储根标识
 * @param outputPath 导出产物相对于存储根的路径
 * @param outputSize 导出产物总字节数
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ExportTaskCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final Long comicId;
    private final String outputRoot; private final String outputPath; private final Long outputSize;
    @JsonCreator
    public ExportTaskCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                    @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId,
                                    @JsonProperty("outputRoot") String outputRoot, @JsonProperty("outputPath") String outputPath,
                                    @JsonProperty("outputSize") Long outputSize) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId; this.outputRoot=outputRoot;
        this.outputPath=outputPath; this.outputSize=outputSize;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public Long comicId(){return comicId;}
    public String outputRoot(){return outputRoot;} public String outputPath(){return outputPath;} public Long outputSize(){return outputSize;}
}
