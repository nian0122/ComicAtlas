package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 导入任务阶段完成事件（Worker → API，routing key: comic.import.task.completed）。
 *
 * <p><b>两阶段语义（冻结）</b>：本事件是导入两阶段最终化的<b>第一阶段（staging）</b>完成信号，
 * 仅表示 staging/metadata 就绪，<b>不</b>代表最终就绪。Worker 在 DB 尚未生成章节 ID 时，以漫画内
 * 暂存键 {@code globalOrder} 把文件先落到 {@code hq/{comicId}/{globalOrder}}；API 收到本事件后
 * 插入章节并取得不可变 {@code chapterId}，再逐章发送 {@link ImportStorageFinalizeRequestedEvent}
 * 请求 Worker 把文件移动到正式 {@code hq/{comicId}/{chapterId}}。comic 进入 READY、import_task
 * 进入 SUCCESS 必须等待全部章节的 {@link ImportStorageFinalizeCompletedEvent}（存储最终化完成）；
 * 最终化失败由 {@link ImportStorageFinalizeFailedEvent} 表示且保持可重试。消费者不得仅凭本事件
 * 进入最终态。
 */
public record ImportTaskCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    String metadataPath
) implements ComicEvent {}
