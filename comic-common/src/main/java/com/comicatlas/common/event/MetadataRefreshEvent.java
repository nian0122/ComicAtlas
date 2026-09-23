package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 元数据刷新请求，由管理端触发，通知 Worker 根据数据库当前内容重新生成漫画 metadata.json。
 * Worker 以原子方式替换文件；该事件不改变漫画、章节或页面的生命周期状态。
 *
 * @param eventId 事件唯一标识，未提供时由紧凑构造器生成
 * @param occurredAt 事件产生时间，未提供时由紧凑构造器填充当前时间
 * @param comicId 待刷新的漫画 ID
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MetadataRefreshEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long comicId;
    @JsonCreator
    public MetadataRefreshEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                @JsonProperty("comicId") Long comicId) {
        this.eventId = eventId == null ? UUID.randomUUID() : eventId;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        this.comicId = comicId;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long comicId(){return comicId;}
}
