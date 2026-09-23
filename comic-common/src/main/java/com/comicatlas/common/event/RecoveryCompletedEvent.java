package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class RecoveryCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final int totalComics; private final int recoveredComics; private final int skippedComics; private final int placeholderComics; private final int errorComics;
    @JsonCreator public RecoveryCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt, @JsonProperty("taskId") Long taskId, @JsonProperty("totalComics") int totalComics, @JsonProperty("recoveredComics") int recoveredComics, @JsonProperty("skippedComics") int skippedComics, @JsonProperty("placeholderComics") int placeholderComics, @JsonProperty("errorComics") int errorComics) { this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.totalComics=totalComics; this.recoveredComics=recoveredComics; this.skippedComics=skippedComics; this.placeholderComics=placeholderComics; this.errorComics=errorComics; }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public int totalComics(){return totalComics;} public int recoveredComics(){return recoveredComics;} public int skippedComics(){return skippedComics;} public int placeholderComics(){return placeholderComics;} public int errorComics(){return errorComics;}
}
