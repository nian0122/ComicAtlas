package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.LqSizeResult;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理命令完成事件（Worker → API）。
 * <p>
 * Worker 完成管理命令后发送此事件。API 端依据 taskId/itemId/attempt
 * 更新 management_task_item 为 SUCCEEDED 并聚合 management_task 状态。
 * transcode 组件仅在 TRANSCODE 操作时携带转码后实测元数据，其余为 null；
 * lqSizes 仅在 LQ_GENERATE / LQ_REGENERATE 操作时携带每页 LQ 产物大小，其余为 null。
 * <p>
 * 为保持事件契约向后兼容，老消息缺少 lqSizes 字段时 Jackson 反序列化为 null。
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
    TranscodeMediaInfo transcode,
    List<LqSizeResult> lqSizes
) implements ComicEvent {

    /**
     * 兼容便捷构造器：不携带 transcode 与 lqSizes（等价于两者为 null）。
     * <p>
     * 保留旧调用签名，避免 TRANSCODE / 无 payload 操作的历史调用点全部改为 11 参。
     */
    public ManagementCommandCompletedEvent(
            UUID eventId,
            Instant occurredAt,
            int version,
            Long taskId,
            Long itemId,
            int attempt,
            String operationType,
            String targetType,
            Long targetId,
            TranscodeMediaInfo transcode) {
        this(eventId, occurredAt, version, taskId, itemId, attempt, operationType, targetType, targetId,
                transcode, null);
    }

    @Override
    public int version() {
        return version;
    }
}
