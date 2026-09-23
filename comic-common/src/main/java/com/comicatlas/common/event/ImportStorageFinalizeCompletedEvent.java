package com.comicatlas.common.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导入存储最终化完成事件（Worker → API，routing key: comic.import.import.storage.finalize.completed）。
 *
 * <p><b>两阶段语义（冻结）</b>：Worker 把 {@code hq/.staging/{taskId}/{comicId}/{globalOrder}} 暂存目录移动到
 * {@code hq/{comicId}/{chapterId}} 后，逐章发送本事件完成两阶段最终化的<b>第二阶段</b>。API 按
 * 章节累加确认，仅当全部章节的 completed 到达（无剩余 PENDING media）时才允许把 comic 置为 READY、
 * import_task 置为 SUCCESS；{@link ImportTaskCompletedEvent} 只表示 staging/metadata 就绪，
 * 不得据此进入最终态。payload 只含 ID 与相对目标目录，禁止绝对路径、Channel 或数据库实体。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ImportStorageFinalizeCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final Long taskId; private final Long comicId;
    private final Integer globalOrder; private final Long chapterId; private final String targetDir; private final int mediaCount;
    @JsonCreator
    public ImportStorageFinalizeCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                               @JsonProperty("taskId") Long taskId, @JsonProperty("comicId") Long comicId,
                                               @JsonProperty("globalOrder") Integer globalOrder, @JsonProperty("chapterId") Long chapterId,
                                               @JsonProperty("targetDir") String targetDir, @JsonProperty("mediaCount") int mediaCount) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.taskId=taskId; this.comicId=comicId; this.globalOrder=globalOrder;
        this.chapterId=chapterId; this.targetDir=targetDir; this.mediaCount=mediaCount;
    }
    public UUID eventId(){return eventId;} public Instant occurredAt(){return occurredAt;} public Long taskId(){return taskId;}
    public Long comicId(){return comicId;} public Integer globalOrder(){return globalOrder;} public Long chapterId(){return chapterId;}
    public String targetDir(){return targetDir;} public int mediaCount(){return mediaCount;}
}
