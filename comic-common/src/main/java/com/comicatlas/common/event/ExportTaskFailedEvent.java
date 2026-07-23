package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record ExportTaskFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String errorCode,
    String errorMessage
) implements ComicEvent {}
