package com.comicatlas.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * LQ 压缩完成事件。
 * Worker 端 Go 工具执行完毕后发送，由 API 侧消费更新 page.lq_status。
 */
public record LqCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long comicId,
    Long chapterId,
    List<Integer> failedPages,
    Integer processed,
    Integer skipped,
    Long elapsedMs
) implements ComicEvent {}
