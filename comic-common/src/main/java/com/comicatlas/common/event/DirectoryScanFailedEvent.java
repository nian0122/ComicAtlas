package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 目录扫描失败事件（Worker → API）。
 * 路径不存在、不可读或扫描异常时回传。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class DirectoryScanFailedEvent implements ComicEvent {
    private final UUID eventId;
    private final Instant occurredAt;
    private final Long taskId;
    private final String errorMessage;

    @JsonCreator
    public DirectoryScanFailedEvent(@JsonProperty("eventId") UUID eventId,
                                    @JsonProperty("occurredAt") Instant occurredAt,
                                    @JsonProperty("taskId") Long taskId,
                                    @JsonProperty("errorMessage") String errorMessage) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.taskId = taskId;
        this.errorMessage = errorMessage;
    }

    public UUID eventId() { return eventId; }
    public Instant occurredAt() { return occurredAt; }
    public Long taskId() { return taskId; }
    public String errorMessage() { return errorMessage; }
}
