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
    @JsonSubTypes.Type(value = TaskStatusChangedEvent.class, name = "TaskStatusChangedEvent"),
    @JsonSubTypes.Type(value = LqGenerateEvent.class, name = "LqGenerateEvent"),
    @JsonSubTypes.Type(value = DeleteRequestedEvent.class, name = "DeleteRequestedEvent"),
    @JsonSubTypes.Type(value = CancelTaskEvent.class, name = "CancelTaskEvent"),
    @JsonSubTypes.Type(value = DeleteCompletedEvent.class, name = "DeleteCompletedEvent"),
    @JsonSubTypes.Type(value = LqCompletedEvent.class, name = "LqCompletedEvent"),
    @JsonSubTypes.Type(value = DeleteHqRequestedEvent.class, name = "DeleteHqRequestedEvent"),
    @JsonSubTypes.Type(value = HqDeletedEvent.class, name = "HqDeletedEvent"),
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
})
public sealed interface ComicEvent
    permits ImportTaskCreatedEvent, ImportTaskCompletedEvent, ImportTaskFailedEvent,
            TaskStatusChangedEvent, LqGenerateEvent, DeleteRequestedEvent,
            CancelTaskEvent, DeleteCompletedEvent, LqCompletedEvent,
            DeleteHqRequestedEvent, HqDeletedEvent,
            ExportTaskCreatedEvent, ExportTaskStartedEvent, ExportTaskCompletedEvent,
            ExportTaskFailedEvent,
            VideoTranscodeRequestedEvent, VideoTranscodeCompletedEvent,
            VideoTranscodeFailedEvent, MetadataRefreshEvent,
            VideoMetadataFixRequestedEvent, VideoMetadataFixCompletedEvent {

    UUID eventId();
    Instant occurredAt();
    default int version() { return 1; }
}
