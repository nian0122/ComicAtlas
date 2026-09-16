package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 漫画导入失败事件，由 Worker 无法完成导入或 API 处理导入结果失败时发布，驱动导入任务进入失败状态。
 * 关联漫画通常保持可诊断的失败/导入中状态，具体清理由导入失败处理流程负责。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 导入任务 ID
 * @param comicId 关联漫画 ID
 * @param errorCode 稳定的错误码，供程序分类处理
 * @param errorMessage 面向日志和任务详情的错误说明
 */
public record ImportTaskFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String errorCode,
    String errorMessage
) implements ComicEvent {}
