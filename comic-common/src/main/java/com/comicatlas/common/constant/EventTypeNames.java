package com.comicatlas.common.constant;

/**
 * Jackson 多态事件名称契约。
 *
 * <p>这些值写入消息体的 {@code eventType} 字段，属于持久化消息协议；新增或修改
 * 必须先完成历史消息、Rabbit 类型头和死信重放兼容性评估。</p>
 */
public final class EventTypeNames {

    public static final String IMPORT_TASK_CREATED = "ImportTaskCreatedEvent";
    public static final String IMPORT_TASK_COMPLETED = "ImportTaskCompletedEvent";
    public static final String IMPORT_TASK_FAILED = "ImportTaskFailedEvent";
    public static final String IMPORT_STORAGE_FINALIZE_REQUESTED = "ImportStorageFinalizeRequestedEvent";
    public static final String IMPORT_STORAGE_FINALIZE_COMPLETED = "ImportStorageFinalizeCompletedEvent";
    public static final String IMPORT_STORAGE_FINALIZE_FAILED = "ImportStorageFinalizeFailedEvent";
    public static final String TASK_STATUS_CHANGED = "TaskStatusChangedEvent";
    public static final String CANCEL_TASK = "CancelTaskEvent";
    public static final String EXPORT_TASK_CREATED = "ExportTaskCreatedEvent";
    public static final String EXPORT_TASK_STARTED = "ExportTaskStartedEvent";
    public static final String EXPORT_TASK_COMPLETED = "ExportTaskCompletedEvent";
    public static final String EXPORT_TASK_FAILED = "ExportTaskFailedEvent";
    public static final String METADATA_REFRESH = "MetadataRefreshEvent";
    public static final String VIDEO_METADATA_FIX_REQUESTED = "VideoMetadataFixRequestedEvent";
    public static final String VIDEO_METADATA_FIX_COMPLETED = "VideoMetadataFixCompletedEvent";
    public static final String RECOVERY_REQUESTED = "RecoveryRequestedEvent";
    public static final String RECOVERY_PROGRESS = "RecoveryProgressEvent";
    public static final String RECOVERY_COMPLETED = "RecoveryCompletedEvent";
    public static final String RECOVERY_FAILED = "RecoveryFailedEvent";
    public static final String RECOVERY_SCAN_COMPLETED = "RecoveryScanCompletedEvent";
    public static final String DIRECTORY_SCAN_REQUESTED = "DirectoryScanRequestedEvent";
    public static final String DIRECTORY_SCAN_COMPLETED = "DirectoryScanCompletedEvent";
    public static final String DIRECTORY_SCAN_FAILED = "DirectoryScanFailedEvent";
    public static final String MANAGEMENT_COMMAND_REQUESTED = "ManagementCommandRequestedEvent";
    public static final String MANAGEMENT_COMMAND_PROGRESS = "ManagementCommandProgressEvent";
    public static final String MANAGEMENT_COMMAND_COMPLETED = "ManagementCommandCompletedEvent";
    public static final String MANAGEMENT_COMMAND_FAILED = "ManagementCommandFailedEvent";
    public static final String MANAGEMENT_COMMAND_CANCEL_REQUESTED = "ManagementCommandCancelRequestedEvent";
    public static final String MEDIA_UPLOAD_COMPLETED = "MediaUploadCompletedEvent";
    public static final String METADATA_REFRESH_SCAN_COMPLETED = "MetadataRefreshScanCompletedEvent";

    private EventTypeNames() {
    }
}
