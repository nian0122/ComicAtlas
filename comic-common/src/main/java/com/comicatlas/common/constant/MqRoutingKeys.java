package com.comicatlas.common.constant;

/**
 * RabbitMQ routing key 常量（契约与 AGENTS.md「事件命名规范（冻结）」一致）。
 * <p>
 * 同一 routing key 可能被多个 exchange 复用（如 task.created 同时用于 comic.import 与 comic.export），
 * 常量名以语义命名，不带 exchange 前缀。
 */
public final class MqRoutingKeys {
    private MqRoutingKeys() {
    }

    // comic.import / comic.export 共用
    public static final String TASK_CREATED = "task.created";
    public static final String TASK_COMPLETED = "task.completed";
    public static final String TASK_FAILED = "task.failed";

    // comic.task
    public static final String STATUS_CHANGED = "status.changed";
    public static final String CANCEL_REQUESTED = "cancel.requested";

    // comic.image
    public static final String LQ_GENERATE = "lq.generate";
    public static final String LQ_COMPLETED = "lq.completed";
    public static final String HQ_DELETE_REQUESTED = "hq.delete.requested";
    public static final String HQ_DELETE_COMPLETED = "hq.delete.completed";
    public static final String VIDEO_METADATA_FIX_REQUESTED = "video.metadata.fix.requested";
    public static final String VIDEO_METADATA_FIX_COMPLETED = "video.metadata.fix.completed";

    // comic.delete
    public static final String DELETE_REQUESTED = "delete.requested";
    public static final String DELETE_COMPLETED = "delete.completed";

    // comic.export
    public static final String TASK_STARTED = "task.started";
    public static final String METADATA_REFRESH_REQUESTED = "metadata.refresh.requested";

    // comic.video
    public static final String VIDEO_TRANSCODE_REQUESTED = "video.transcode.requested";
    public static final String VIDEO_TRANSCODE_COMPLETED = "video.transcode.completed";
    public static final String VIDEO_TRANSCODE_FAILED = "video.transcode.failed";

    // comic.recovery
    public static final String RECOVERY_REQUESTED = "recovery.requested";
    public static final String RECOVERY_PROGRESS = "recovery.progress";
    public static final String RECOVERY_COMPLETED = "recovery.completed";
    public static final String RECOVERY_FAILED = "recovery.failed";

    // comic.scan
    public static final String SCAN_REQUESTED = "scan.requested";
    public static final String SCAN_COMPLETED = "scan.completed";
    public static final String SCAN_FAILED = "scan.failed";

    // comic.management
    public static final String COMMAND_REQUESTED = "command.requested";
    public static final String COMMAND_COMPLETED = "command.completed";
    public static final String COMMAND_FAILED = "command.failed";
    public static final String COMMAND_PROGRESS = "command.progress";
    public static final String COMMAND_CANCEL = "command.cancel";
}
