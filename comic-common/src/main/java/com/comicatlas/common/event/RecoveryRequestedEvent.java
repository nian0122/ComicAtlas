package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

public record RecoveryRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId
) implements ComicEvent {}
