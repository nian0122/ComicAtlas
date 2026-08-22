package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入存储最终化完成事件（Worker → API，routing key: comic.import.import.storage.finalize.completed）。
 *
 * <p><b>两阶段语义（冻结）</b>：Worker 把 {@code hq/.staging/{taskId}/{comicId}/{globalOrder}} 暂存目录移动到
 * {@code hq/{comicId}/{chapterId}} 后，逐章发送本事件完成两阶段最终化的<b>第二阶段</b>。API 按
 * 章节累加确认，仅当全部章节的 completed 到达（无剩余 PENDING media）时才允许把 comic 置为 READY、
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
