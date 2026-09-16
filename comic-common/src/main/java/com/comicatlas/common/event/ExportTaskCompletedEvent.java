package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导出任务成功事件，由 Worker 原子发布导出产物后发送，驱动 API 将任务置为成功并登记产物信息。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导出任务 ID
 * @param comicId 导出的漫画 ID
 * @param outputRoot 导出产物所在存储根标识
 * @param outputPath 导出产物相对于存储根的路径
 * @param outputSize 导出产物总字节数
 */
public record ExportTaskCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String outputRoot,
    String outputPath,
    Long outputSize
) implements ComicEvent {}
