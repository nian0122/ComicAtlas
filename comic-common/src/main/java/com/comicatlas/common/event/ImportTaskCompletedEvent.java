package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入任务阶段完成事件（Worker → API，routing key: comic.import.task.completed）。
 *
 * <p><b>状态语义（冻结）</b>：本事件仅表示导入 staging/metadata 阶段就绪，<b>不</b>代表最终
 * 就绪。comic 进入 READY、import_task 进入 SUCCESS 必须等待
 * {@link ImportStorageFinalizeCompletedEvent}（存储最终化完成）；最终化失败由
 * {@link ImportStorageFinalizeFailedEvent} 表示且保持可重试。消费者不得仅凭本事件进入最终态。
 */
public record ImportTaskCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String metadataPath
) implements ComicEvent {}
