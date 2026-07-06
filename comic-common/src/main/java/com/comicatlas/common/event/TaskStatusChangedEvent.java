package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record TaskStatusChangedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    String status,
    int progress,
    String downloadMethod,
    long speedBytesPerSec,
    int etaSeconds
) implements ComicEvent {}
