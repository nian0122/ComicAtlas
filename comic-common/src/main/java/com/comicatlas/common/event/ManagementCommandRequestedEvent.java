package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令请求事件（API → Worker）。
 * <p>
 * 统一 envelope：eventId, occurredAt, version, taskId, itemId,
 * attempt, operationType, targetType, targetId。
 * Worker 根据 operationType + targetType + targetId 路由到具体处理器，
 * 执行中通过 {@link ManagementCommandProgressEvent} 报告进度，
 * 完成后通过 {@link ManagementCommandCompletedEvent} /
 * {@link ManagementCommandFailedEvent} 回传结果。
 * <p>
 * manifestTaskId：TRASH 清单任务 ID（TRASH/{targetType}/{targetId}/{manifestTaskId}/）。
 * TRASH 操作时为空（用自身 taskId）；RESTORE/PURGE 操作时必须指向发起回收的任务。
 */
public record ManagementCommandRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId,
    Long manifestTaskId
) implements ComicEvent {

    /**
     * 兼容便捷构造器：TRASH/普通媒体操作不携带 manifestTaskId。
     * <p>
     * 等价于 {@code manifestTaskId = null}。RESTORE/PURGE 等需要指向
     * 发起回收任务的操作必须使用 10 参全量构造器。
     */
    public ManagementCommandRequestedEvent(
            UUID eventId,
            Instant occurredAt,
            int version,
            Long taskId,
            Long itemId,
            int attempt,
            String operationType,
            String targetType,
            Long targetId) {
        this(eventId, occurredAt, version, taskId, itemId, attempt, operationType, targetType, targetId, null);
    }

    @Override
    public int version() {
        return version;
    }
}
