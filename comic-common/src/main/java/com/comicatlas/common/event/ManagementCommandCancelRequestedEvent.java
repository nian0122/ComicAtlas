package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理命令取消请求事件（API → Worker）。
 * <p>
 * API 端取消管理任务时发送，Worker 收到后在 {@code ConcurrentHashMap}
 * 中标记取消，正在执行的处理器在下一检查点退出并将 item 标记为 CANCELLED。
 */
public record ManagementCommandCancelRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId
) implements ComicEvent {

    @Override
    public int version() {
        return version;
    }
}
