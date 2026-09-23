package com.comicatlas.common.event;

import com.comicatlas.common.dto.ScanResultDTO;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 目录扫描完成事件（Worker → API）。
 * result 携带扫描到的漫画候选子目录列表。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class DirectoryScanCompletedEvent implements ComicEvent {
    private final UUID eventId;
    private final Instant occurredAt;
    private final Long taskId;
    private final ScanResultDTO result;

    @JsonCreator
    public DirectoryScanCompletedEvent(@JsonProperty("eventId") UUID eventId,
                                       @JsonProperty("occurredAt") Instant occurredAt,
                                       @JsonProperty("taskId") Long taskId,
                                       @JsonProperty("result") ScanResultDTO result) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.taskId = taskId;
        this.result = result;
    }

    public UUID eventId() { return eventId; }
    public Instant occurredAt() { return occurredAt; }
    public Long taskId() { return taskId; }
    public ScanResultDTO result() { return result; }
}
