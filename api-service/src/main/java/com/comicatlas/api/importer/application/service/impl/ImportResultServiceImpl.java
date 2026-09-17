package com.comicatlas.api.importer.application.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.application.port.out.ImportResultPersistencePort;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
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

    public Map<String, Object> readMetadata(Long taskId) throws IOException {
        return objectMapper.readValue(storageProperties.root("METADATA").resolve(taskId + ".json").toFile(),
                new TypeReference<Map<String, Object>>() { });
    }

    public boolean isTerminal(Long taskId) {
        ImportTask task = persistencePort.findImportTask(taskId);
        return task != null && TERMINAL_STATUSES.contains(task.getStatus());
    }

    @Transactional
    public void applyStatus(Long taskId, String newStatus, Integer progress, long speed,
                            Integer eta, String downloadMethod, String errorMessage) {
        ImportTask task = persistencePort.findImportTask(taskId);
        if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) {
            return;
        }
        ImportTaskStatus mappedStatus = parseStatus(newStatus);
        if (mappedStatus != null) {
            task.setStatus(mappedStatus);
        }
        if ("DOWNLOADING".equals(newStatus) && task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        task.setProgress(progress);
        if (speed > 0) {
            task.setDownloadSpeed(speed);
        }
        if (eta != null && eta > 0) {
            task.setEtaSeconds(eta);
        }
        if (downloadMethod != null) {
            task.setDownloadMethod(downloadMethod);
        }
        if ("FAILED".equals(newStatus) && errorMessage != null && !errorMessage.isBlank()) {
            task.setErrorMessage(errorMessage);
            task.setEndTime(LocalDateTime.now());
        }
        persistencePort.updateImportTask(task);
        if (task.getManagementTaskId() != null) {
            var stage = com.comicatlas.api.task.domain.model.TaskStage.fromStatus(newStatus);
            if (stage != null) {
                managementTaskService.updateStage(task.getManagementTaskId(), stage, progress);
            }
        }
        if (task.getManagementTaskId() != null
                && ("FAILED".equals(newStatus) || "CANCELLED".equals(newStatus))) {
            ManagementTaskItem item = managementTaskService.findActiveItem(
                    "COMIC", task.getComicId(), TaskType.IMPORT);
            if (item != null) {
                ManagementTaskStatus itemStatus = "CANCELLED".equals(newStatus)
                        ? ManagementTaskStatus.CANCELLED : ManagementTaskStatus.FAILED;
                managementTaskService.updateItemStatus(item.getId(), itemStatus,
                        task.getErrorMessage(), "IMPORT_TASK", task.getId());
            }
        }
        if ("FAILED".equals(newStatus)) {
            markComicImportFailed(task);
        }
    }

    @Transactional
    public void applyFailed(Long taskId, String errorCode, String errorMessage) {
        ImportTask task = persistencePort.findImportTask(taskId);
        if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) {
            return;
        }
        task.setStatus(ImportTaskStatus.FAILED);
        task.setEndTime(LocalDateTime.now());
        if (errorCode != null) {
            task.setErrorMessage(errorCode + ": " + errorMessage);
        }
        else if (errorMessage != null) {
            task.setErrorMessage(errorMessage);
        }
        persistencePort.updateImportTask(task);
        markImportFailed(task);
    }

    private void markImportFailed(ImportTask task) {
        ImportResultPersistencePort.ComicSnapshot comic = markComicImportFailed(task);
        if (comic == null) {
            return;
        }
        ManagementTaskItem item = managementTaskService.findActiveItem(
                "COMIC", comic.id(), TaskType.IMPORT);
        if (item != null) {
            managementTaskService.updateItemStatus(item.getId(),
                    ManagementTaskStatus.FAILED, task.getErrorMessage(), "IMPORT_TASK", task.getId());
        }
    }

    private ImportResultPersistencePort.ComicSnapshot markComicImportFailed(ImportTask task) {
        ImportResultPersistencePort.ComicSnapshot comic = persistencePort.findComic(task.getComicId());
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
