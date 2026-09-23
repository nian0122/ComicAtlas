package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.LqSizeResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理命令失败事件（Worker → API）。
 * <p>
 * Worker 执行管理命令失败时发送此事件。API 端依据 taskId/itemId/attempt
 * 更新 management_task_item 为 FAILED 并聚合 management_task 状态。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ManagementCommandFailedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final int version; private final Long taskId; private final Long itemId; private final int attempt; private final String operationType; private final String targetType; private final Long targetId; private final String errorMessage; private final List<LqSizeResult> lqSizes;
    @JsonCreator
    public ManagementCommandFailedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("version") int version, @JsonProperty("taskId") Long taskId, @JsonProperty("itemId") Long itemId, @JsonProperty("attempt") int attempt, @JsonProperty("operationType") String operationType, @JsonProperty("targetType") String targetType, @JsonProperty("targetId") Long targetId, @JsonProperty("errorMessage") String errorMessage, @JsonProperty("lqSizes") List<LqSizeResult> lqSizes) { this.eventId=eventId; this.occurredAt=occurredAt; this.version=version; this.taskId=taskId; this.itemId=itemId; this.attempt=attempt; this.operationType=operationType; this.targetType=targetType; this.targetId=targetId; this.errorMessage=errorMessage; this.lqSizes=lqSizes; }

    /** 兼容不携带 LQ 部分成功结果的失败事件构造方式。 */
    public ManagementCommandFailedEvent(
            UUID eventId, Instant occurredAt, int version, Long taskId, Long itemId, int attempt,
            String operationType, String targetType, Long targetId, String errorMessage) {
        this(eventId, occurredAt, version, taskId, itemId, attempt,
                operationType, targetType, targetId, errorMessage, null);
    }
    public UUID eventId(){return eventId; } public Instant occurredAt(){return occurredAt; } public Long taskId(){return taskId; } public Long itemId(){return itemId; } public int attempt(){return attempt; } public String operationType(){return operationType; } public String targetType(){return targetType; } public Long targetId(){return targetId; } public String errorMessage(){return errorMessage; } public List<LqSizeResult> lqSizes(){return lqSizes; }

    @Override
    public int version() {
        return version;
    }
}
