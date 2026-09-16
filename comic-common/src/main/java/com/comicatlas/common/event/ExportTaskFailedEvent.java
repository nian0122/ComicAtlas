package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导出任务失败事件，由 Worker 无法生成导出产物时发布，驱动 API 将任务置为失败并保存错误信息。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 导出的漫画 ID
 * @param errorCode 稳定的错误码
 * @param errorMessage 导出失败原因
 */
public record ExportTaskFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String errorCode,
    String errorMessage
) implements ComicEvent {}
