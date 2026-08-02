package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 目录扫描请求事件（API → Worker）。
 * Worker 消费后检查宿主机路径存在性并遍历目录，结果通过
 * {@link DirectoryScanCompletedEvent} / {@link DirectoryScanFailedEvent} 回传。
 */
public record DirectoryScanRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    String directoryPath
) implements ComicEvent {}
