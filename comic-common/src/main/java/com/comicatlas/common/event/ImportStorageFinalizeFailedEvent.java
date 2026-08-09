package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入存储最终化失败事件（Worker → API，routing key: comic.import.import.storage.finalize.failed）。
 *
 * <p><b>状态语义（冻结）</b>：最终化失败时发送本事件，comic 不得进入 READY、task 不得进入
 * SUCCESS；失败保持可重试（重试事件使用新的 eventId 以便 inbox 幂等）。payload 只含 ID 与
 * 错误码/错误消息，禁止绝对路径、Channel 或数据库实体。
 */
public record ImportStorageFinalizeFailedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    Integer globalOrder,
    Long chapterId,
    String errorCode,
    String errorMessage
) implements ComicEvent {}
