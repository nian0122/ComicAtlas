package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理任务取消请求，由用户取消任务时发布，通知 Worker 设置取消标记并尽快停止处理。
 * API 侧任务最终状态由后续状态事件或任务处理结果决定，不由本事件直接完成状态落库。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 待取消的任务 ID
 * @param comicId 任务关联的漫画 ID，可为空
 */
public record CancelTaskEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {}
