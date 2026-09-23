package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 漫画导入失败事件，由 Worker 无法完成导入或 API 处理导入结果失败时发布，驱动导入任务进入失败状态。
 * 关联漫画通常保持可诊断的失败/导入中状态，具体清理由导入失败处理流程负责。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导入任务 ID
 * @param comicId 关联漫画 ID
 * @param errorCode 稳定的错误码，供程序分类处理
 * @param errorMessage 面向日志和任务详情的错误说明
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ImportTaskFailedEvent implements ComicEvent {
    private final UUID eventId;
    private final Instant occurredAt;
    private final Long taskId;
    private final Long comicId;
    private final String errorCode;
    private final String errorMessage;

    @JsonCreator
    public ImportTaskFailedEvent(@JsonProperty("eventId") UUID eventId,
                                 @JsonProperty("occurredAt") Instant occurredAt,
                                 @JsonProperty("taskId") Long taskId,
                                 @JsonProperty("comicId") Long comicId,
                                 @JsonProperty("errorCode") String errorCode,
                                 @JsonProperty("errorMessage") String errorMessage) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.taskId = taskId;
        this.comicId = comicId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public UUID eventId() { return eventId; }
    public Instant occurredAt() { return occurredAt; }
    public Long taskId() { return taskId; }
    public Long comicId() { return comicId; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
}
