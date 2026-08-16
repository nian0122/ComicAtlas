package com.comicatlas.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ImportTaskCreatedEvent.class, name = "ImportTaskCreatedEvent"),
    @JsonSubTypes.Type(value = ImportTaskCompletedEvent.class, name = "ImportTaskCompletedEvent"),
    @JsonSubTypes.Type(value = ImportTaskFailedEvent.class, name = "ImportTaskFailedEvent"),
    @JsonSubTypes.Type(value = ImportStorageFinalizeRequestedEvent.class, name = "ImportStorageFinalizeRequestedEvent"),
    @JsonSubTypes.Type(value = ImportStorageFinalizeCompletedEvent.class, name = "ImportStorageFinalizeCompletedEvent"),
    @JsonSubTypes.Type(value = ImportStorageFinalizeFailedEvent.class, name = "ImportStorageFinalizeFailedEvent"),
    @JsonSubTypes.Type(value = TaskStatusChangedEvent.class, name = "TaskStatusChangedEvent"),
    @JsonSubTypes.Type(value = CancelTaskEvent.class, name = "CancelTaskEvent"),
    @JsonSubTypes.Type(value = ExportTaskCreatedEvent.class, name = "ExportTaskCreatedEvent"),
    @JsonSubTypes.Type(value = ExportTaskStartedEvent.class, name = "ExportTaskStartedEvent"),
    @JsonSubTypes.Type(value = ExportTaskCompletedEvent.class, name = "ExportTaskCompletedEvent"),
    @JsonSubTypes.Type(value = ExportTaskFailedEvent.class, name = "ExportTaskFailedEvent"),
    @JsonSubTypes.Type(value = VideoTranscodeRequestedEvent.class, name = "VIDEO_TRANSCODE_REQUESTED"),
    @JsonSubTypes.Type(value = VideoTranscodeCompletedEvent.class, name = "VIDEO_TRANSCODE_COMPLETED"),
    @JsonSubTypes.Type(value = VideoTranscodeFailedEvent.class, name = "VIDEO_TRANSCODE_FAILED"),
    @JsonSubTypes.Type(value = MetadataRefreshEvent.class, name = "MetadataRefreshEvent"),
    @JsonSubTypes.Type(value = VideoMetadataFixRequestedEvent.class, name = "VideoMetadataFixRequestedEvent"),
    @JsonSubTypes.Type(value = VideoMetadataFixCompletedEvent.class, name = "VideoMetadataFixCompletedEvent"),
    @JsonSubTypes.Type(value = RecoveryRequestedEvent.class, name = "RecoveryRequestedEvent"),
    @JsonSubTypes.Type(value = RecoveryProgressEvent.class, name = "RecoveryProgressEvent"),
    @JsonSubTypes.Type(value = RecoveryCompletedEvent.class, name = "RecoveryCompletedEvent"),
    @JsonSubTypes.Type(value = RecoveryFailedEvent.class, name = "RecoveryFailedEvent"),
    @JsonSubTypes.Type(value = RecoveryScanCompletedEvent.class, name = "RecoveryScanCompletedEvent"),
    @JsonSubTypes.Type(value = DirectoryScanRequestedEvent.class, name = "DirectoryScanRequestedEvent"),
    @JsonSubTypes.Type(value = DirectoryScanCompletedEvent.class, name = "DirectoryScanCompletedEvent"),
    @JsonSubTypes.Type(value = DirectoryScanFailedEvent.class, name = "DirectoryScanFailedEvent"),
    @JsonSubTypes.Type(value = ManagementCommandRequestedEvent.class, name = "ManagementCommandRequestedEvent"),
    @JsonSubTypes.Type(value = ManagementCommandProgressEvent.class, name = "ManagementCommandProgressEvent"),
    @JsonSubTypes.Type(value = ManagementCommandCompletedEvent.class, name = "ManagementCommandCompletedEvent"),
    @JsonSubTypes.Type(value = ManagementCommandFailedEvent.class, name = "ManagementCommandFailedEvent"),
    @JsonSubTypes.Type(value = ManagementCommandCancelRequestedEvent.class, name = "ManagementCommandCancelRequestedEvent"),
    @JsonSubTypes.Type(value = MediaUploadCompletedEvent.class, name = "MediaUploadCompletedEvent"),
    @JsonSubTypes.Type(value = MetadataRefreshScanCompletedEvent.class, name = "MetadataRefreshScanCompletedEvent"),
})
public sealed interface ComicEvent
    permits ImportTaskCreatedEvent, ImportTaskCompletedEvent, ImportTaskFailedEvent,
            ImportStorageFinalizeRequestedEvent, ImportStorageFinalizeCompletedEvent,
            ImportStorageFinalizeFailedEvent,
            TaskStatusChangedEvent,
            CancelTaskEvent,
            ExportTaskCreatedEvent, ExportTaskStartedEvent, ExportTaskCompletedEvent,
            ExportTaskFailedEvent,
            VideoTranscodeRequestedEvent, VideoTranscodeCompletedEvent,
            VideoTranscodeFailedEvent, MetadataRefreshEvent,
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
