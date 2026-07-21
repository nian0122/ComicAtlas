package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record DeleteHqRequestedEvent(
    UUID eventId, Instant occurredAt, Long comicId, Long chapterId, String chapterNo, String scope
) implements ComicEvent {}
