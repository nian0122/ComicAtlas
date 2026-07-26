package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record MetadataRefreshEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId
) implements ComicEvent {
    public MetadataRefreshEvent {
        if (eventId == null) eventId = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
