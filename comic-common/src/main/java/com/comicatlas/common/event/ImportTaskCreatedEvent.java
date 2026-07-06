package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record ImportTaskCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String sourceType,
    String sourcePath
) implements ComicEvent {}
