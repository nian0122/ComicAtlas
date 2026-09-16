package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 视频元数据修复请求，由管理端发现漫画存在缺失视频元数据时发布，触发 Worker 使用 ffprobe 扫描视频。
 * Worker 完成扫描后发布 {@link VideoMetadataFixCompletedEvent}；本事件不直接改变数据库状态。
 *
 * @param eventId 事件唯一标识，未提供时由紧凑构造器生成
 * @param occurredAt 事件产生时间，未提供时由紧凑构造器填充当前时间
 * @param comicId 待修复视频元数据的漫画 ID
 */
public record VideoMetadataFixRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId
) implements ComicEvent {
    public VideoMetadataFixRequestedEvent {
        if (eventId == null) { eventId = UUID.randomUUID(); }
        if (occurredAt == null) { occurredAt = Instant.now(); }
    }
}
