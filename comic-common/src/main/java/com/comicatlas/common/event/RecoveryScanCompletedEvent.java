package com.comicatlas.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Worker 完成 HQ 目录扫描后发送的结果事件。
 * 包含所有待恢复的漫画 ID 列表，由 API 侧的 RecoveryEventHandler 消费并逐本调用 RecoveryEngine。
 */
public record RecoveryScanCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    List<Long> comicIds
) implements ComicEvent {}
