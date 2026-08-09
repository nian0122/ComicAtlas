package com.comicatlas.common.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;

import java.time.Instant;
import java.util.UUID;

/**
 * 元数据扫盘刷新扫描完成事件（Worker → API）。
 * <p>
 * Worker 完成 HQ 目录重扫并落盘快照 JSON 后发送此事件。API 端依据
 * taskId/itemId/attempt 更新管理任务项状态，并按快照内容与数据库比对刷新元数据。
 * <p>
 * 消息契约：复用 {@link MqExchanges#MANAGEMENT} + {@link MqRoutingKeys#COMMAND_COMPLETED}
 * + {@link MqQueues#MANAGEMENT_RESULT}，<b>不新增队列</b>——本事件只是元数据扫盘
 * 刷新命令在 Worker 侧完成时回传的完成结果，与其它管理命令完成事件共用结果队列。
 * <p>
 * snapshotRef/snapshotSha256/snapshotBytes/schemaVersion 描述已落盘快照产物：
 * 引用路径（本地产物路径）、SHA-256 校验、字节数与快照 schema 版本，
 * 便于 API 端按引用读取并校验完整性。
 */
public record MetadataRefreshScanCompletedEvent(
    UUID eventId,
    Instant occurredAt,
    int version,
    Long taskId,
    Long itemId,
    int attempt,
    String operationType,
    String targetType,
    Long targetId,
    String snapshotRef,
    String snapshotSha256,
    long snapshotBytes,
    int schemaVersion
) implements ComicEvent {

    @Override
    public int version() {
        return version;
    }
}
