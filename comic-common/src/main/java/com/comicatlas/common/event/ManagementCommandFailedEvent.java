package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令失败事件（Worker → API）。
 * <p>
 * Worker 执行管理命令失败时发送此事件。API 端依据 taskId/itemId/attempt
 * 更新 management_task_item 为 FAILED 并聚合 management_task 状态。
 */
public record ManagementCommandFailedEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId,
    String errorMessage
) implements ComicEvent {

    @Override
    public int version() {
        return version;
    }
}
