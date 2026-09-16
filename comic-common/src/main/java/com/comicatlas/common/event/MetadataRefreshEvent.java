package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 元数据刷新请求，由管理端触发，通知 Worker 根据数据库当前内容重新生成漫画 metadata.json。
 * Worker 以原子方式替换文件；该事件不改变漫画、章节或页面的生命周期状态。
 *
 * @param eventId 事件唯一标识，未提供时由紧凑构造器生成
 * @param occurredAt 事件产生时间，未提供时由紧凑构造器填充当前时间
 * @param comicId 待刷新的漫画 ID
 */
public record MetadataRefreshEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId
) implements ComicEvent {
    public MetadataRefreshEvent {
        if (eventId == null) { eventId = UUID.randomUUID(); }
        if (occurredAt == null) { occurredAt = Instant.now(); }
    }
}
