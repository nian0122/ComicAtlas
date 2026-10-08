package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 目录扫描请求事件（API → Worker）。
 * Worker 消费后检查宿主机路径存在性并遍历目录，结果通过
 * {@link DirectoryScanCompletedEvent} / {@link DirectoryScanFailedEvent} 回传。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class DirectoryScanRequestedEvent implements ComicEvent {
    private final UUID eventId;
    private final Instant occurredAt;
    private final Long taskId;
    private final String directoryPath;

    @JsonCreator
    public DirectoryScanRequestedEvent(@JsonProperty("eventId") UUID eventId,
                                       @JsonProperty("occurredAt") Instant occurredAt,
                                       @JsonProperty("taskId") Long taskId,
                                       @JsonProperty("directoryPath") String directoryPath) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.taskId = taskId;
        this.directoryPath = directoryPath;
    }

    public UUID eventId() { return eventId; }
    public Instant occurredAt() { return occurredAt; }
    public Long taskId() { return taskId; }
    public String directoryPath() { return directoryPath; }
}
