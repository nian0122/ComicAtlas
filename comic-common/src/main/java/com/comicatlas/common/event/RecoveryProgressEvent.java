package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据恢复进度事件，由恢复引擎处理一批扫描结果后发布，供 API 更新恢复任务的统计进度。
 * 该事件表示中间进度，不将任务置为完成或失败。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 恢复任务 ID
 * @param totalComics 本次恢复扫描发现的漫画总数
 * @param recoveredComics 已成功恢复的漫画数
 * @param skippedComics 因数据库已有有效记录等原因跳过的漫画数
 * @param placeholderComics 仅创建占位记录的漫画数
 * @param errorComics 恢复失败的漫画数
 */
public record RecoveryProgressEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    int totalComics,
    int recoveredComics,
    int skippedComics,
    int placeholderComics,
    int errorComics
) implements ComicEvent {}
