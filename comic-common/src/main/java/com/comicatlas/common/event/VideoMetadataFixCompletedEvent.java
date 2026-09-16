package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.VideoMetadataFixResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 视频元数据修复完成事件，由 Worker 扫描完目标漫画的视频后发布，供 API 回写 page 的尺寸、时长和编码信息。
 * 空结果表示本次没有找到可修复的视频，不应因此将已有媒体标记为失败。
 *
 * @param eventId 事件唯一标识，未提供时由紧凑构造器生成
 * @param occurredAt 事件产生时间，未提供时由紧凑构造器填充当前时间
 * @param comicId 已扫描的漫画 ID
 * @param results 成功分析的视频元数据列表，未提供时规范化为空列表
 */
public record VideoMetadataFixCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    List<VideoMetadataFixResult> results
) implements ComicEvent {
    public VideoMetadataFixCompletedEvent {
        if (eventId == null) { eventId = UUID.randomUUID(); }
        if (occurredAt == null) { occurredAt = Instant.now(); }
        if (results == null) { results = Collections.emptyList(); }
    }
}
