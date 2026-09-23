package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 数据恢复任务失败事件，由 Worker 或 API 恢复流程无法继续时发布，驱动任务进入失败状态并记录原因。
 * 已完成的单本恢复结果是否保留由恢复服务的事务边界决定，本事件不回滚已提交记录。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 恢复任务 ID
 * @param errorMessage 可供诊断的失败原因
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class RecoveryFailedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final String errorMessage;
    @JsonCreator public RecoveryFailedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("taskId") Long taskId, @JsonProperty("errorMessage") String errorMessage) { this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.errorMessage=errorMessage; }
    public UUID eventId(){return eventId; } public Instant occurredAt(){return occurredAt; } public Long taskId(){return taskId; } public String errorMessage(){return errorMessage; }
}
