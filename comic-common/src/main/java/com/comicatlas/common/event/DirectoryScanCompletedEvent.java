package com.comicatlas.common.event;

import com.comicatlas.common.dto.ScanResultVO;

import java.time.Instant;
import java.util.UUID;

/**
 * 目录扫描完成事件（Worker → API）。
 * result 携带扫描到的漫画候选子目录列表。
 */
public record DirectoryScanCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    ScanResultVO result
) implements ComicEvent {}
