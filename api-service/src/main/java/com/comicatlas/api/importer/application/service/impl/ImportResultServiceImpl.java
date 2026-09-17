package com.comicatlas.api.importer.application.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.application.port.out.ImportResultPersistencePort;
import com.comicatlas.api.importer.application.port.out.ImportManagementTaskQueryPort;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.domain.service.ManagementStateMachine;
import com.comicatlas.contract.common.enums.ComicStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 导入结果应用服务：统一承载结果事件的文件读取、状态流转和失败联动。 */
@Service
@RequiredArgsConstructor
public class ImportResultServiceImpl implements com.comicatlas.api.importer.application.port.in.ImportResultService {
    private static final Set<ImportTaskStatus> TERMINAL_STATUSES =
            EnumSet.of(ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);

    private final ObjectMapper objectMapper;
    private final ApiStorageProperties storageProperties;
    private final ImportResultPersistencePort persistencePort;
    private final ManagementTaskService managementTaskService;
    private final ImportManagementTaskQueryPort managementTaskQueryPort;

    public Map<String, Object> readMetadata(Long taskId) throws IOException {
        return objectMapper.readValue(storageProperties.root("METADATA").resolve(taskId + ".json").toFile(),
                new TypeReference<Map<String, Object>>() { });
    }

    public boolean isTerminal(Long taskId) {
        ImportResultPersistencePort.ImportTaskSnapshot task = persistencePort.findImportTask(taskId);
        return task != null && TERMINAL_STATUSES.contains(task.status());
    }

    @Transactional
    public void applyStatus(Long taskId, String newStatus, Integer progress, long speed,
                            Integer eta, String downloadMethod, String errorMessage) {
        ImportResultPersistencePort.ImportTaskSnapshot task = persistencePort.findImportTask(taskId);
        if (task == null || TERMINAL_STATUSES.contains(task.status())) {
            return;
        }
        ImportTaskStatus mappedStatus = parseStatus(newStatus);
        if (mappedStatus == null) {
            mappedStatus = task.status();
        }
        LocalDateTime startTime = task.startTime();
        if ("DOWNLOADING".equals(newStatus) && startTime == null) {
            startTime = LocalDateTime.now();
        }
        Long downloadSpeed = speed > 0 ? speed : null;
        Integer etaSeconds = eta != null && eta > 0 ? eta : null;
        String resolvedDownloadMethod = downloadMethod;
        String resolvedErrorMessage = task.errorMessage();
        LocalDateTime endTime = null;
        if ("FAILED".equals(newStatus) && errorMessage != null && !errorMessage.isBlank()) {
            resolvedErrorMessage = errorMessage;
            endTime = LocalDateTime.now();
        }
        persistencePort.updateImportTask(new ImportResultPersistencePort.ImportTaskUpdateCommand(
                task.id(), task.comicId(), task.managementTaskId(), mappedStatus, progress, downloadSpeed,
                etaSeconds, resolvedDownloadMethod, resolvedErrorMessage, startTime, endTime));
        if (task.managementTaskId() != null) {
            var stage = com.comicatlas.api.task.domain.model.TaskStage.fromStatus(newStatus);
            if (stage != null) {
                managementTaskService.updateStage(task.managementTaskId(), stage, progress);
            }
        }
        if (task.managementTaskId() != null
                && ("FAILED".equals(newStatus) || "CANCELLED".equals(newStatus))) {
            ImportManagementTaskQueryPort.ItemSnapshot item = managementTaskQueryPort.findActiveItem(
                    "COMIC", task.comicId(), TaskType.IMPORT);
            if (item != null) {
                ManagementTaskStatus itemStatus = "CANCELLED".equals(newStatus)
                        ? ManagementTaskStatus.CANCELLED : ManagementTaskStatus.FAILED;
                managementTaskService.updateItemStatus(item.id(), itemStatus,
                        resolvedErrorMessage, "IMPORT_TASK", task.id());
            }
        }
        if ("FAILED".equals(newStatus)) {
            markComicImportFailed(task);
        }
    }

    @Transactional
    public void applyFailed(Long taskId, String errorCode, String errorMessage) {
        ImportResultPersistencePort.ImportTaskSnapshot task = persistencePort.findImportTask(taskId);
        if (task == null || TERMINAL_STATUSES.contains(task.status())) {
            return;
        }
        LocalDateTime endTime = LocalDateTime.now();
        String resolvedErrorMessage = task.errorMessage();
        if (errorCode != null) {
            resolvedErrorMessage = errorCode + ": " + errorMessage;
        }
        else if (errorMessage != null) {
            resolvedErrorMessage = errorMessage;
        }
        persistencePort.updateImportTask(new ImportResultPersistencePort.ImportTaskUpdateCommand(
                task.id(), task.comicId(), task.managementTaskId(), ImportTaskStatus.FAILED, 0, null,
                null, null, resolvedErrorMessage, task.startTime(), endTime));
        markImportFailed(task, resolvedErrorMessage);
    }

    private void markImportFailed(ImportResultPersistencePort.ImportTaskSnapshot task, String errorMessage) {
        ImportResultPersistencePort.ComicSnapshot comic = markComicImportFailed(task);
        if (comic == null) {
            return;
        }
        ImportManagementTaskQueryPort.ItemSnapshot item = managementTaskQueryPort.findActiveItem(
                "COMIC", comic.id(), TaskType.IMPORT);
        if (item != null) {
            managementTaskService.updateItemStatus(item.id(),
                    ManagementTaskStatus.FAILED, errorMessage, "IMPORT_TASK", task.id());
        }
    }

    private ImportResultPersistencePort.ComicSnapshot markComicImportFailed(
            ImportResultPersistencePort.ImportTaskSnapshot task) {
        ImportResultPersistencePort.ComicSnapshot comic = persistencePort.findComic(task.comicId());
        if (comic == null || comic.status() != ComicStatus.IMPORTING) {
            return comic;
        }
        ManagementStateMachine.validateComicTransition(comic.status().name(), "IMPORT_FAILED");
        persistencePort.updateComic(new ImportResultPersistencePort.ComicStatusUpdateCommand(
                comic.id(), ComicStatus.IMPORT_FAILED, comic.version()));
        return new ImportResultPersistencePort.ComicSnapshot(comic.id(), ComicStatus.IMPORT_FAILED, comic.version());
    }

    private static ImportTaskStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try { return ImportTaskStatus.valueOf(status); }
        catch (IllegalArgumentException exception) { return null; }
    }
}
