package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据恢复请求，由 API 创建恢复任务后发布，触发 Worker 扫描 HQ 存储并回传可恢复的漫画 ID。
 * 该事件启动恢复流程，不直接写入业务表；恢复结果由后续进度、完成或失败事件驱动。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 恢复任务 ID
 */
public record RecoveryRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId
) implements ComicEvent {}
