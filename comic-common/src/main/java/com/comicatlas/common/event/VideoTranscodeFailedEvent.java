package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record VideoTranscodeFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long pageId,
    Long comicId,
    String errorMessage
) implements ComicEvent {}
