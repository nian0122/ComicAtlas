package com.comicatlas.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ImportTaskCreatedEvent.class, name = "ImportTaskCreated"),
    @JsonSubTypes.Type(value = ImportTaskCompletedEvent.class, name = "ImportTaskCompleted"),
    @JsonSubTypes.Type(value = ImportTaskFailedEvent.class, name = "ImportTaskFailed"),
    @JsonSubTypes.Type(value = TaskStatusChangedEvent.class, name = "TaskStatusChanged"),
    @JsonSubTypes.Type(value = LqGenerateEvent.class, name = "LqGenerate"),
    @JsonSubTypes.Type(value = DeleteRequestedEvent.class, name = "DeleteRequested"),
    @JsonSubTypes.Type(value = CancelTaskEvent.class, name = "CancelTask"),
    @JsonSubTypes.Type(value = DeleteCompletedEvent.class, name = "DeleteCompleted"),
    @JsonSubTypes.Type(value = LqCompletedEvent.class, name = "LqCompleted"),
    @JsonSubTypes.Type(value = DeleteHqRequestedEvent.class, name = "DeleteHqRequested"),
    @JsonSubTypes.Type(value = HqDeletedEvent.class, name = "HqDeleted"),
    @JsonSubTypes.Type(value = ExportTaskCreatedEvent.class, name = "ExportTaskCreated"),
    @JsonSubTypes.Type(value = ExportTaskStartedEvent.class, name = "ExportTaskStarted"),
    @JsonSubTypes.Type(value = ExportTaskCompletedEvent.class, name = "ExportTaskCompleted"),
    @JsonSubTypes.Type(value = ExportTaskFailedEvent.class, name = "ExportTaskFailed")
})
public sealed interface ComicEvent
    permits ImportTaskCreatedEvent, ImportTaskCompletedEvent, ImportTaskFailedEvent,
            TaskStatusChangedEvent, LqGenerateEvent, DeleteRequestedEvent,
            CancelTaskEvent, DeleteCompletedEvent, LqCompletedEvent,
            DeleteHqRequestedEvent, HqDeletedEvent,
            ExportTaskCreatedEvent, ExportTaskStartedEvent, ExportTaskCompletedEvent,
            ExportTaskFailedEvent {

    UUID eventId();
    Instant occurredAt();
    default int version() { return 1; }
}
