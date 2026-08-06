package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令完成事件（Worker → API）。
 * <p>
 * Worker 完成管理命令后发送此事件。API 端依据 taskId/itemId/attempt
 * 更新 management_task_item 为 SUCCEEDED 并聚合 management_task 状态。
 * transcode 组件仅在 TRANSCODE 操作时携带转码后实测元数据，其余为 null。
 */
public record ManagementCommandCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId,
    TranscodeMediaInfo transcode
) implements ComicEvent {

    @Override
    public int version() {
        return version;
    }
}
