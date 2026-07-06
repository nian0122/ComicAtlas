package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record ImportTaskCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String metadataPath
) implements ComicEvent {}
