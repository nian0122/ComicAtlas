package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.LqGenerationResult;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令完成事件（Worker → API）。
 * <p>
 * Worker 完成管理命令后发送此事件。API 端依据 taskId/itemId/attempt
 * 更新 management_task_item 为 SUCCEEDED 并聚合 management_task 状态。
 * <p>
 * transcode 组件仅在 TRANSCODE 操作时携带转码后实测元数据，其余为 null；
 * lqResult 组件仅在 LQ 操作时携带逐媒体生成结果，其余为 null。
 * 二者按 operationType 二选一，另一个为 null；API 端不得以 null payload
 * 猜测整章/整本结果，逐媒体结果必须按 lqResult 的媒体条目逐个落库。
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
    LqGenerationResult lqResult
) implements ComicEvent {

    /**
     * 旧 10 参兼容构造：不携带 LQ 结果（lqResult 为 null）。
     * 供既有发布器、handler 与测试在未接入 LQ 结果前继续编译使用。
     */
    public ManagementCommandCompletedEvent(UUID eventId, Instant occurredAt, int version,
                                           Long taskId, Long itemId, int attempt,
                                           String operationType, String targetType, Long targetId,
                                           TranscodeMediaInfo transcode) {
        this(eventId, occurredAt, version, taskId, itemId, attempt,
                operationType, targetType, targetId, transcode, null);
    }

    @Override
    public int version() {
        return version;
    }
}
