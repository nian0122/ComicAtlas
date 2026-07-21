package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record HqDeletedEvent(
    UUID eventId, Instant occurredAt, Long comicId, Long chapterId, Long freedBytes, Integer deletedCount
) implements ComicEvent {}
