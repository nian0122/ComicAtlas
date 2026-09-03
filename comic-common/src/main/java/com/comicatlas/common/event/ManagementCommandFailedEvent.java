package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.LqSizeResult;

import java.time.Instant;
import java.util.List;
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
    String errorMessage,
    List<LqSizeResult> lqSizes
) implements ComicEvent {

    /** 兼容不携带 LQ 部分成功结果的失败事件构造方式。 */
    public ManagementCommandFailedEvent(
            UUID eventId, Instant occurredAt, int version, Long taskId, Long itemId, int attempt,
            String operationType, String targetType, Long targetId, String errorMessage) {
        this(eventId, occurredAt, version, taskId, itemId, attempt,
                operationType, targetType, targetId, errorMessage, null);
    }

    @Override
    public int version() {
        return version;
    }
}
