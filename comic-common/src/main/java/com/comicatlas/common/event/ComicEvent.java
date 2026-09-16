package com.comicatlas.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.comicatlas.common.constant.EventTypeNames;

import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ImportTaskCreatedEvent.class, name = EventTypeNames.IMPORT_TASK_CREATED),
    @JsonSubTypes.Type(value = ImportTaskCompletedEvent.class, name = EventTypeNames.IMPORT_TASK_COMPLETED),
    @JsonSubTypes.Type(value = ImportTaskFailedEvent.class, name = EventTypeNames.IMPORT_TASK_FAILED),
    @JsonSubTypes.Type(value = ImportStorageFinalizeRequestedEvent.class, name = EventTypeNames.IMPORT_STORAGE_FINALIZE_REQUESTED),
    @JsonSubTypes.Type(value = ImportStorageFinalizeCompletedEvent.class, name = EventTypeNames.IMPORT_STORAGE_FINALIZE_COMPLETED),
    @JsonSubTypes.Type(value = ImportStorageFinalizeFailedEvent.class, name = EventTypeNames.IMPORT_STORAGE_FINALIZE_FAILED),
    @JsonSubTypes.Type(value = TaskStatusChangedEvent.class, name = EventTypeNames.TASK_STATUS_CHANGED),
    @JsonSubTypes.Type(value = CancelTaskEvent.class, name = EventTypeNames.CANCEL_TASK),
    @JsonSubTypes.Type(value = ExportTaskCreatedEvent.class, name = EventTypeNames.EXPORT_TASK_CREATED),
    @JsonSubTypes.Type(value = ExportTaskStartedEvent.class, name = EventTypeNames.EXPORT_TASK_STARTED),
    @JsonSubTypes.Type(value = ExportTaskCompletedEvent.class, name = EventTypeNames.EXPORT_TASK_COMPLETED),
    @JsonSubTypes.Type(value = ExportTaskFailedEvent.class, name = EventTypeNames.EXPORT_TASK_FAILED),
    @JsonSubTypes.Type(value = MetadataRefreshEvent.class, name = EventTypeNames.METADATA_REFRESH),
    @JsonSubTypes.Type(value = VideoMetadataFixRequestedEvent.class, name = EventTypeNames.VIDEO_METADATA_FIX_REQUESTED),
    @JsonSubTypes.Type(value = VideoMetadataFixCompletedEvent.class, name = EventTypeNames.VIDEO_METADATA_FIX_COMPLETED),
    @JsonSubTypes.Type(value = RecoveryRequestedEvent.class, name = EventTypeNames.RECOVERY_REQUESTED),
    @JsonSubTypes.Type(value = RecoveryProgressEvent.class, name = EventTypeNames.RECOVERY_PROGRESS),
    @JsonSubTypes.Type(value = RecoveryCompletedEvent.class, name = EventTypeNames.RECOVERY_COMPLETED),
    @JsonSubTypes.Type(value = RecoveryFailedEvent.class, name = EventTypeNames.RECOVERY_FAILED),
    @JsonSubTypes.Type(value = RecoveryScanCompletedEvent.class, name = EventTypeNames.RECOVERY_SCAN_COMPLETED),
    @JsonSubTypes.Type(value = DirectoryScanRequestedEvent.class, name = EventTypeNames.DIRECTORY_SCAN_REQUESTED),
    @JsonSubTypes.Type(value = DirectoryScanCompletedEvent.class, name = EventTypeNames.DIRECTORY_SCAN_COMPLETED),
    @JsonSubTypes.Type(value = DirectoryScanFailedEvent.class, name = EventTypeNames.DIRECTORY_SCAN_FAILED),
    @JsonSubTypes.Type(value = ManagementCommandRequestedEvent.class, name = EventTypeNames.MANAGEMENT_COMMAND_REQUESTED),
    @JsonSubTypes.Type(value = ManagementCommandProgressEvent.class, name = EventTypeNames.MANAGEMENT_COMMAND_PROGRESS),
    @JsonSubTypes.Type(value = ManagementCommandCompletedEvent.class, name = EventTypeNames.MANAGEMENT_COMMAND_COMPLETED),
    @JsonSubTypes.Type(value = ManagementCommandFailedEvent.class, name = EventTypeNames.MANAGEMENT_COMMAND_FAILED),
    @JsonSubTypes.Type(value = ManagementCommandCancelRequestedEvent.class, name = EventTypeNames.MANAGEMENT_COMMAND_CANCEL_REQUESTED),
    @JsonSubTypes.Type(value = MediaUploadCompletedEvent.class, name = EventTypeNames.MEDIA_UPLOAD_COMPLETED),
    @JsonSubTypes.Type(value = MetadataRefreshScanCompletedEvent.class, name = EventTypeNames.METADATA_REFRESH_SCAN_COMPLETED),
})
public sealed interface ComicEvent
    permits ImportTaskCreatedEvent, ImportTaskCompletedEvent, ImportTaskFailedEvent,
            ImportStorageFinalizeRequestedEvent, ImportStorageFinalizeCompletedEvent,
            ImportStorageFinalizeFailedEvent,
            TaskStatusChangedEvent,
            CancelTaskEvent,
            ExportTaskCreatedEvent, ExportTaskStartedEvent, ExportTaskCompletedEvent,
            ExportTaskFailedEvent,
            MetadataRefreshEvent,
            VideoMetadataFixRequestedEvent, VideoMetadataFixCompletedEvent,
            RecoveryRequestedEvent, RecoveryProgressEvent, RecoveryCompletedEvent,
            RecoveryFailedEvent, RecoveryScanCompletedEvent,
            DirectoryScanRequestedEvent, DirectoryScanCompletedEvent, DirectoryScanFailedEvent,
            ManagementCommandRequestedEvent, ManagementCommandProgressEvent,
            ManagementCommandCompletedEvent, ManagementCommandFailedEvent,
            ManagementCommandCancelRequestedEvent,
            MediaUploadCompletedEvent,
            MetadataRefreshScanCompletedEvent {

    UUID eventId();
    Instant occurredAt();
    default int version() { return 1; }
}
