package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理命令进度事件（Worker → API）。
 * <p>
 * Worker 在执行管理命令过程中（如 LQ 生成、HQ 删除）定期发送进度，
 * API 端更新 management_task_item.progress 和 management_task.progress。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ManagementCommandProgressEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final int version; private final Long taskId; private final Long itemId; private final int attempt; private final String operationType; private final String targetType; private final Long targetId; private final int progress; private final String stage;
    @JsonCreator
    public ManagementCommandProgressEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("version") int version, @JsonProperty("taskId") Long taskId, @JsonProperty("itemId") Long itemId, @JsonProperty("attempt") int attempt, @JsonProperty("operationType") String operationType, @JsonProperty("targetType") String targetType, @JsonProperty("targetId") Long targetId, @JsonProperty("progress") int progress, @JsonProperty("stage") String stage) { this.eventId=eventId; this.occurredAt=occurredAt; this.version=version; this.taskId=taskId; this.itemId=itemId; this.attempt=attempt; this.operationType=operationType; this.targetType=targetType; this.targetId=targetId; this.progress=progress; this.stage=stage; }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public Long itemId(){return itemId;} public int attempt(){return attempt;} public String operationType(){return operationType;} public String targetType(){return targetType;} public Long targetId(){return targetId;} public int progress(){return progress;} public String stage(){return stage;}

    @Override
    public int version() {
        return version;
    }
}
