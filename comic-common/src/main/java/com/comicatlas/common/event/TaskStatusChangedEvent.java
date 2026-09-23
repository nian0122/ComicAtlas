package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 异步任务状态变化事件，由 Worker 在处理过程中或结束时发布，供 API 更新任务进度、速度及错误信息。
 * 状态值由任务类型对应的状态机解释；事件不会直接改变漫画媒体状态。
 *
 * @param eventId 事件唯一标识
 * @param occurredAt 事件产生时间
 * @param taskId 任务 ID
 * @param status 当前任务状态
 * @param progress 当前进度百分比
 * @param downloadMethod 下载方式，例如 Archiver 或 Torrent
 * @param speedBytesPerSec 当前下载速度，单位为字节/秒
 * @param etaSeconds 预计剩余秒数，不可估算时通常为零或约定的无效值
 * @param errorMessage 任务错误信息；正常状态时为空
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TaskStatusChangedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final String status; private final int progress;
    private final String downloadMethod; private final long speedBytesPerSec; private final int etaSeconds; private final String errorMessage;
    @JsonCreator
    public TaskStatusChangedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                  @JsonProperty("taskId") Long taskId, @JsonProperty("status") String status,
                                  @JsonProperty("progress") int progress, @JsonProperty("downloadMethod") String downloadMethod,
                                  @JsonProperty("speedBytesPerSec") long speedBytesPerSec, @JsonProperty("etaSeconds") int etaSeconds,
                                  @JsonProperty("errorMessage") String errorMessage) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.status=status; this.progress=progress;
        this.downloadMethod=downloadMethod; this.speedBytesPerSec=speedBytesPerSec; this.etaSeconds=etaSeconds; this.errorMessage=errorMessage;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;} public String status(){return status;}
    public int progress(){return progress;} public String downloadMethod(){return downloadMethod;} public long speedBytesPerSec(){return speedBytesPerSec;} public int etaSeconds(){return etaSeconds;} public String errorMessage(){return errorMessage;}
}
