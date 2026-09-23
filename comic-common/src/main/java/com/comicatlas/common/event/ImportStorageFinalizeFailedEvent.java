package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导入存储最终化失败事件（Worker → API，routing key: comic.import.import.storage.finalize.failed）。
 *
 * <p><b>两阶段语义（冻结）</b>：某章从 {@code hq/.staging/{taskId}/{comicId}/{globalOrder}}（staging 暂存）到
 * {@code hq/{comicId}/{chapterId}}（最终位置）的最终化失败时发送本事件，comic 不得进入 READY、
 * task 不得进入 SUCCESS；失败保持可重试（重试事件使用新的 eventId 以便 inbox 幂等）。payload
 * 只含 ID 与错误码/错误消息，禁止绝对路径、Channel 或数据库实体。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ImportStorageFinalizeFailedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final Long comicId;
    private final Integer globalOrder; private final Long chapterId; private final String errorCode; private final String errorMessage;
    @JsonCreator
    public ImportStorageFinalizeFailedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                             @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId,
                                             @JsonProperty("globalOrder") Integer globalOrder, @JsonProperty("chapterId") Long chapterId,
                                             @JsonProperty("errorCode") String errorCode, @JsonProperty("errorMessage") String errorMessage) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId; this.globalOrder=globalOrder;
        this.chapterId=chapterId; this.errorCode=errorCode; this.errorMessage=errorMessage;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;}
    public Long comicId(){return comicId;} public Integer globalOrder(){return globalOrder;} public Long chapterId(){return chapterId;}
    public String errorCode(){return errorCode;} public String errorMessage(){return errorMessage;}
}
