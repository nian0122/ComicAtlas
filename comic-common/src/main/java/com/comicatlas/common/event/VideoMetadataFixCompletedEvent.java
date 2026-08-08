package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.VideoMetadataFixResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record VideoMetadataFixCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    List<VideoMetadataFixResult> results
) implements ComicEvent {
    public VideoMetadataFixCompletedEvent {
        if (eventId == null) { eventId = UUID.randomUUID(); }
        if (occurredAt == null) { occurredAt = Instant.now(); }
        if (results == null) { results = Collections.emptyList(); }
    }
}
