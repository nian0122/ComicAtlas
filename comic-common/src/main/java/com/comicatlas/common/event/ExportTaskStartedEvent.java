package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导出任务开始事件，由 Worker 开始收集或构建导出产物时发布，驱动 API 将任务标记为运行中。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 正在导出的漫画 ID
 */
public record ExportTaskStartedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {}
