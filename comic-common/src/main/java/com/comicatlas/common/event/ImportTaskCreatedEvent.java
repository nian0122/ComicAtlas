package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 漫画导入任务创建事件，由 API 预创建漫画和导入任务后发布，触发 Worker 按来源类型执行导入。
 * 发布后任务进入异步处理流程，漫画保持 IMPORTING，直到各章节存储最终化完成才转为 READY。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导入任务 ID
 * @param comicId 预创建的漫画 ID
 * @param sourceType 来源类型，如 ZIP、REGISTER 或 EHENTAI
 * @param sourcePath 来源路径或来源标识
 */
public record ImportTaskCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String sourceType,
    String sourcePath
) implements ComicEvent {}
