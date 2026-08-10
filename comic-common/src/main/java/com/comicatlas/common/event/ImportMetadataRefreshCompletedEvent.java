package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入最终化元数据重建完成事件（Worker → API，routing key: comic.import.import.metadata.refresh.completed）。
 *
 * <p><b>导入收尾语义（冻结）</b>：Worker 完成磁盘 {@code metadata/{comicId}.json} 重建（hqPath 均为
 * {@code {comicId}/{chapterId}/...} 最终布局）后发送本事件，API 据此把 comic 置为 READY、
 * import_task 置为 SUCCESS。payload 只含 ID，禁止绝对路径、Channel 或数据库实体。
 */
public record ImportMetadataRefreshCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId
) implements ComicEvent {}
