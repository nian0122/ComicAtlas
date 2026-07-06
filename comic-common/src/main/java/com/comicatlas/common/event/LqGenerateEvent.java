package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record LqGenerateEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    Long chapterId
) implements ComicEvent {}
