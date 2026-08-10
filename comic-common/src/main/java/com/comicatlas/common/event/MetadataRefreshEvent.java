package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 元数据重建请求事件（API → Worker，routing key: comic.export.metadata.refresh.requested）。
 *
 * <p><b>两种触发来源</b>：
 * <ul>
 *   <li>旧调用点（普通元数据重建）：{@code taskId} 为 {@code null}，Worker 保持旧语义——仅重建
 *       JSON 并 ACK，不发布任何结果事件。</li>
 *   <li>导入最终化收尾（两阶段最终化）：{@code taskId} 携带 import_task.id，Worker 重建成功后须
 *       回传 {@link ImportMetadataRefreshCompletedEvent}/{@link ImportMetadataRefreshFailedEvent}
 *       结果事件（comic.import exchange），API 据此才把 comic/task 置为终态。</li>
 * </ul>
 *
 * <p>兼容性：旧消息反序列化时 {@code taskId} 为 {@code null}，走旧语义，不影响既有调用点。
 */
public record MetadataRefreshEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {
    public MetadataRefreshEvent {
        if (eventId == null) { eventId = UUID.randomUUID(); }
        if (occurredAt == null) { occurredAt = Instant.now(); }
    }

    /** 旧调用点便捷构造：不携带 taskId（非导入触发）。 */
    public MetadataRefreshEvent(UUID eventId, Instant occurredAt, Long comicId) {
        this(eventId, occurredAt, null, comicId);
    }
}
