package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record RecoveryProgressEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    int totalComics,
    int recoveredComics,
    int skippedComics,
    int placeholderComics,
    int errorComics
) implements ComicEvent {}
