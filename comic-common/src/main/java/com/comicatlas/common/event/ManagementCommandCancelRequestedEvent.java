package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 管理命令取消请求事件（API → Worker）。
 * <p>
 * API 端取消管理任务时发送，Worker 收到后在 {@code ConcurrentHashMap}
 * 中标记取消，正在执行的处理器在下一检查点退出并将 item 标记为 CANCELLED。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ManagementCommandCancelRequestedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final int version; private final Long taskId;
    private final Long itemId; private final int attempt; private final String operationType; private final String targetType; private final Long targetId;
    @JsonCreator
    public ManagementCommandCancelRequestedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                                 @JsonProperty("version") int version, @JsonProperty("taskId") Long taskId,
                                                 @JsonProperty("itemId") Long itemId, @JsonProperty("attempt") int attempt,
                                                 @JsonProperty("operationType") String operationType, @JsonProperty("targetType") String targetType,
                                                 @JsonProperty("targetId") Long targetId) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.version=version; this.taskId=taskId; this.itemId=itemId;
        this.attempt=attempt; this.operationType=operationType; this.targetType=targetType; this.targetId=targetId;
    }
    public UUID eventId(){return eventId; } public Instant occurredAt(){return occurredAt; } public Long taskId(){return taskId; } public Long itemId(){return itemId; }
    public int attempt(){return attempt; } public String operationType(){return operationType; } public String targetType(){return targetType; } public Long targetId(){return targetId; }

    @Override
    public int version() {
        return version;
    }
}
