package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record VideoTranscodeCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long pageId,
    Long comicId,
    String newHqPath,
    String container,
    String videoCodec,
    String audioCodec,
    Long fileSize
) implements ComicEvent {}
