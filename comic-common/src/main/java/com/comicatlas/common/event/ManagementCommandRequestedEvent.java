package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理命令请求事件（API → Worker）。
 * <p>
 * 统一 envelope：eventId, occurredAt, version, taskId, itemId,
 * attempt, operationType, targetType, targetId。
 * Worker 根据 operationType + targetType + targetId 路由到具体处理器，
 * 执行中通过 {@link ManagementCommandProgressEvent} 报告进度，
 * 完成后通过 {@link ManagementCommandCompletedEvent} /
 * {@link ManagementCommandFailedEvent} 回传结果。
 * <p>
 * manifestTaskId：TRASH 清单任务 ID（TRASH/{targetType}/{targetId}/{manifestTaskId}/）。
 * TRASH 操作时为空（用自身 taskId）；RESTORE/PURGE 操作时必须指向发起回收的任务。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ManagementCommandRequestedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final int version; private final Long taskId; private final Long itemId; private final int attempt;
    private final String operationType; private final String targetType; private final Long targetId; private final Long manifestTaskId;
    @JsonCreator
    public ManagementCommandRequestedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("version") int version,
                                           @JsonProperty("taskId") Long taskId, @JsonProperty("itemId") Long itemId, @JsonProperty("attempt") int attempt,
                                           @JsonProperty("operationType") String operationType, @JsonProperty("targetType") String targetType, @JsonProperty("targetId") Long targetId,
                                           @JsonProperty("manifestTaskId") Long manifestTaskId) { this.eventId=eventId; this.occurredAt=occurredAt; this.version=version; this.taskId=taskId; this.itemId=itemId; this.attempt=attempt; this.operationType=operationType; this.targetType=targetType; this.targetId=targetId; this.manifestTaskId=manifestTaskId; }

    /**
     * 兼容便捷构造器：TRASH/普通媒体操作不携带 manifestTaskId。
     * <p>
     * 等价于 {@code manifestTaskId = null}。RESTORE/PURGE 等需要指向
     * 发起回收任务的操作必须使用 10 参全量构造器。
     */
    public ManagementCommandRequestedEvent(
            UUID eventId,
            Instant occurredAt,
            int version,
            Long taskId,
            Long itemId,
            int attempt,
            String operationType,
            String targetType,
            Long targetId) {
        this(eventId, occurredAt, version, taskId, itemId, attempt, operationType, targetType, targetId, null);
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public Long itemId(){return itemId;} public int attempt(){return attempt;} public String operationType(){return operationType;} public String targetType(){return targetType;} public Long targetId(){return targetId;} public Long manifestTaskId(){return manifestTaskId;}

    @Override
    public int version() {
        return version;
    }
}
