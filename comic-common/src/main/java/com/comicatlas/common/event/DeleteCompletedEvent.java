package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record DeleteCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    int deletedDirs,
    int deletedFiles
) implements ComicEvent {}
