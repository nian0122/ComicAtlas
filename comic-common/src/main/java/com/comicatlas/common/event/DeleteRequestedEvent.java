package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record DeleteRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId
) implements ComicEvent {}
