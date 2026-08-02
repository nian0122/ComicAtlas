package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 目录扫描失败事件（Worker → API）。
 * 路径不存在、不可读或扫描异常时回传。
 */
public record DirectoryScanFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    String errorMessage
) implements ComicEvent {}
