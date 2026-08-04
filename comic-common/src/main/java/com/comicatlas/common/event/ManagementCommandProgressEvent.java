package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令进度事件（Worker → API）。
 * <p>
 * Worker 在执行管理命令过程中（如 LQ 生成、HQ 删除）定期发送进度，
 * API 端更新 management_task_item.progress 和 management_task.progress。
 */
public record ManagementCommandProgressEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId,
    int progress,
    String stage
) implements ComicEvent {

    @Override
    public int version() {
        return version;
    }
}
