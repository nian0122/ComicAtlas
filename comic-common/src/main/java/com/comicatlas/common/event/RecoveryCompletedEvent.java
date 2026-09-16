package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据恢复任务完成事件，由恢复流程处理完全部扫描结果后发布，驱动任务进入成功状态并保存最终统计。
 * 统计字段反映本次任务各处理结果的数量，不代表漫画内容本身被重新编码。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 恢复任务 ID
 * @param totalComics 本次恢复扫描发现的漫画总数
 * @param recoveredComics 已成功恢复的漫画数
 * @param skippedComics 被跳过的漫画数
 * @param placeholderComics 仅创建占位记录的漫画数
 * @param errorComics 恢复失败的漫画数
 */
public record RecoveryCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    int totalComics,
    int recoveredComics,
    int skippedComics,
    int placeholderComics,
    int errorComics
) implements ComicEvent {}
