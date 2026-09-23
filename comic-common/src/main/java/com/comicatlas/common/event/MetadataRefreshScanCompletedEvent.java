package com.comicatlas.common.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 元数据类扫描完成事件（Worker → API）。
 * <p>
 * Worker 完成 HQ 目录重扫并落盘快照 JSON 后发送此事件。{@code operationType=METADATA_REFRESH}
 * 由 API 刷新已有媒体并登记快照中发现的新增媒体。
 * 两种流程都只由 Worker 读取和分析文件，数据库写入由 API 负责。
 * <p>
 * 消息契约：复用 {@link MqExchanges#MANAGEMENT} + {@link MqRoutingKeys#COMMAND_COMPLETED}
 * + {@link MqQueues#MANAGEMENT_RESULT}，<b>不新增队列</b>——本事件只是元数据扫盘
 * 刷新命令在 Worker 侧完成时回传的完成结果，与其它管理命令完成事件共用结果队列。
 * <p>
 * snapshotRef/snapshotSha256/snapshotBytes/schemaVersion 描述已落盘快照产物：
 * 引用路径（本地产物路径）、SHA-256 校验、字节数与快照 schema 版本，
 * 便于 API 端按引用读取并校验完整性。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MetadataRefreshScanCompletedEvent implements ComicEvent {
    private final UUID eventId; private final Instant occurredAt; private final int version; private final Long taskId; private final Long itemId;
    private final int attempt; private final String operationType; private final String targetType; private final Long targetId;
    private final String snapshotRef; private final String snapshotSha256; private final long snapshotBytes; private final int schemaVersion;
    @JsonCreator
    public MetadataRefreshScanCompletedEvent(@JsonProperty("eventId") UUID eventId, @JsonProperty("occurredAt") Instant occurredAt,
                                             @JsonProperty("version") int version, @JsonProperty("taskId") Long taskId,
                                             @JsonProperty("itemId") Long itemId, @JsonProperty("attempt") int attempt,
                                             @JsonProperty("operationType") String operationType, @JsonProperty("targetType") String targetType,
                                             @JsonProperty("targetId") Long targetId, @JsonProperty("snapshotRef") String snapshotRef,
                                             @JsonProperty("snapshotSha256") String snapshotSha256, @JsonProperty("snapshotBytes") long snapshotBytes,
                                             @JsonProperty("schemaVersion") int schemaVersion) {
        this.eventId=eventId; this.occurredAt=occurredAt; this.version=version; this.taskId=taskId; this.itemId=itemId; this.attempt=attempt;
        this.operationType=operationType; this.targetType=targetType; this.targetId=targetId; this.snapshotRef=snapshotRef;
        this.snapshotSha256=snapshotSha256; this.snapshotBytes=snapshotBytes; this.schemaVersion=schemaVersion;
    }
    public UUID eventId(){return eventId; } public Instant occurredAt(){return occurredAt; } public Long taskId(){return taskId; } public Long itemId(){return itemId; }
    public int attempt(){return attempt; } public String operationType(){return operationType; } public String targetType(){return targetType; } public Long targetId(){return targetId; }
    public String snapshotRef(){return snapshotRef; } public String snapshotSha256(){return snapshotSha256; } public long snapshotBytes(){return snapshotBytes; } public int schemaVersion(){return schemaVersion; }

    @Override
    public int version() {
        return version;
    }
}
