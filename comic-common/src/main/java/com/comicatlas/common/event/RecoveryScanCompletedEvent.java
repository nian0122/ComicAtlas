package com.comicatlas.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Worker 完成 HQ 目录扫描后发送的结果事件。
 * 包含所有待恢复的漫画 ID 列表，由 API 侧的 RecoveryEventHandler 消费并逐本调用 RecoveryEngine。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class RecoveryScanCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final List<Long> comicIds;
    @JsonCreator public RecoveryScanCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("taskId") Long taskId, @JsonProperty("comicIds") List<Long> comicIds) { this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicIds=comicIds; }
    public UUID eventId(){return eventId; } public Instant occurredAt(){return occurredAt; } public Long taskId(){return taskId; } public List<Long> comicIds(){return comicIds; }
}
