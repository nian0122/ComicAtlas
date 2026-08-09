package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入存储最终化完成事件（Worker → API，routing key: comic.import.import.storage.finalize.completed）。
 *
 * <p><b>状态语义（冻结）</b>：仅在本事件到达后，API 才允许把对应 comic 置为 READY、
 * import_task 置为 SUCCESS；{@link ImportTaskCompletedEvent} 只表示 staging/metadata 就绪，
 * 不得据此进入最终态。payload 只含 ID 与相对目标目录，禁止绝对路径、Channel 或数据库实体。
 */
public record ImportStorageFinalizeCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    Integer globalOrder,
    Long chapterId,
    String targetDir,
    int mediaCount
) implements ComicEvent {}
