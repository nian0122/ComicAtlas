package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record RecoveryFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    String errorMessage
) implements ComicEvent {}
