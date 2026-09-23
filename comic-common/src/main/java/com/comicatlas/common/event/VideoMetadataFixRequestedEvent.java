package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 视频元数据修复请求，由管理端发现漫画存在缺失视频元数据时发布，触发 Worker 使用 ffprobe 扫描视频。
 * Worker 完成扫描后发布 {@link VideoMetadataFixCompletedEvent}；本事件不直接改变数据库状态。
 *
 * @param eventId 事件唯一标识，未提供时由紧凑构造器生成
 * @param occurredAt 事件产生时间，未提供时由紧凑构造器填充当前时间
 * @param comicId 待修复视频元数据的漫画 ID
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class VideoMetadataFixRequestedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long comicId;
    @JsonCreator
    public VideoMetadataFixRequestedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                          @JsonProperty("comicId") Long comicId) {
        this.eventId = eventId == null ? UUID.randomUUID() : eventId;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        this.comicId = comicId;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long comicId(){return comicId;}
}
