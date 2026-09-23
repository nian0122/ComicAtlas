package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.VideoMetadataFixResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 视频元数据修复完成事件，由 Worker 扫描完目标漫画的视频后发布，供 API 回写 page 的尺寸、时长和编码信息。
 * 空结果表示本次没有找到可修复的视频，不应因此将已有媒体标记为失败。
 *
 * @param eventId 事件唯一标识，未提供时由紧凑构造器生成
 * @param occurredAt 事件产生时间，未提供时由紧凑构造器填充当前时间
 * @param comicId 已扫描的漫画 ID
 * @param results 成功分析的视频元数据列表，未提供时规范化为空列表
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class VideoMetadataFixCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long comicId; private final List<VideoMetadataFixResult> results;
    @JsonCreator
    public VideoMetadataFixCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                          @JsonProperty("comicId") Long comicId, @JsonProperty("results") List<VideoMetadataFixResult> results) {
        this.eventId = eventId == null ? UUID.randomUUID() : eventId;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        this.comicId = comicId;
        this.results = results == null ? Collections.emptyList() : results;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long comicId(){return comicId;} public List<VideoMetadataFixResult> results(){return results;}
}
