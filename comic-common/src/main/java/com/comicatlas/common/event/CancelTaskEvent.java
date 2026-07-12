package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record CancelTaskEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {}
