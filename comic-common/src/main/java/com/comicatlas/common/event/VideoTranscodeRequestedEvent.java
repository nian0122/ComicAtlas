package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record VideoTranscodeRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    Long pageId,
    String hqRoot,
    String hqPath,
    String container
) implements ComicEvent {}
