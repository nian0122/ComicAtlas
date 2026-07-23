package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record ExportTaskCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String outputRoot,
    String outputPath,
    Long outputSize
) implements ComicEvent {}
