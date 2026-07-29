package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record RecoveryCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    int totalComics,
    int recoveredComics,
    int skippedComics,
    int placeholderComics,
    int errorComics
) implements ComicEvent {}
