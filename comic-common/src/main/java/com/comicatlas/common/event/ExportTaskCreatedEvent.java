package com.comicatlas.common.event;

import com.comicatlas.common.constant.ExportFormats;
import java.time.Instant;
import java.util.UUID;

public record ExportTaskCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String format
) implements ComicEvent {
    public ExportTaskCreatedEvent(UUID eventId, Instant occurredAt, Long taskId, Long comicId) {
        this(eventId, occurredAt, taskId, comicId, ExportFormats.ZIP);
    }
}
