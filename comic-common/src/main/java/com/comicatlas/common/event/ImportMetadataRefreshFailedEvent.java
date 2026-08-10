package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入最终化元数据重建失败事件（Worker → API，routing key: comic.import.import.metadata.refresh.failed）。
 *
 * <p><b>导入收尾语义（冻结）</b>：磁盘 {@code metadata/{comicId}.json} 重建失败时发送本事件，comic 不得
 * 进入 READY、task 不得进入 SUCCESS；失败保持可重试（重试事件使用新的 eventId 以便 inbox 幂等）。
 * payload 只含 ID 与错误码/脱敏错误消息，禁止绝对路径、Channel 或数据库实体。
 */
public record ImportMetadataRefreshFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String errorCode,
    String errorMessage
) implements ComicEvent {}
