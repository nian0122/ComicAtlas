package com.comicatlas.api.importer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.api.importer.persistence.mapper.ImportTaskMapper;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.task.state.ManagementStateMachine;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
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
// TODO(LAYER-14): 具体 Service 实现位于 service 包，未与 Service 接口及 service/impl 实现分离。
@RequiredArgsConstructor
public class ImportResultService {
    private static final Set<ImportTaskStatus> TERMINAL_STATUSES =
            EnumSet.of(ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);

    private final ObjectMapper objectMapper;
    private final ApiStorageProperties storageProperties;
    private final ImportTaskMapper taskMapper;
    private final ComicMapper comicMapper;
    private final ManagementTaskService managementTaskService;

    public Map<String, Object> readMetadata(Long taskId) throws IOException {
        return objectMapper.readValue(storageProperties.root("METADATA").resolve(taskId + ".json").toFile(),
                new TypeReference<Map<String, Object>>() { });
    }

    public boolean isTerminal(Long taskId) {
        ImportTask task = taskMapper.selectById(taskId);
        return task != null && TERMINAL_STATUSES.contains(task.getStatus());
    }

    @Transactional
    public void applyStatus(Long taskId, String newStatus, Integer progress, long speed,
                            Integer eta, String downloadMethod, String errorMessage) {
        ImportTask task = taskMapper.selectById(taskId);
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
        taskMapper.updateById(task);
        if (task.getManagementTaskId() != null) {
            var stage = com.comicatlas.api.task.enums.TaskStage.fromStatus(newStatus);
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
        ImportTask task = taskMapper.selectById(taskId);
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
        taskMapper.updateById(task);
        markImportFailed(task);
    }

    private void markImportFailed(ImportTask task) {
        Comic comic = markComicImportFailed(task);
        if (comic == null) {
            return;
        }
        ManagementTaskItem item = managementTaskService.findActiveItem(
                "COMIC", comic.getId(), TaskType.IMPORT);
        if (item != null) {
            managementTaskService.updateItemStatus(item.getId(),
                    ManagementTaskStatus.FAILED, task.getErrorMessage(), "IMPORT_TASK", task.getId());
        }
    }

    private Comic markComicImportFailed(ImportTask task) {
        Comic comic = comicMapper.selectById(task.getComicId());
        if (comic == null || comic.getStatus() != ComicStatus.IMPORTING) {
            return comic;
        }
        ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "IMPORT_FAILED");
        comic.setStatus(ComicStatus.IMPORT_FAILED);
        comicMapper.updateById(comic);
        return comic;
    }

    private static ImportTaskStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try { return ImportTaskStatus.valueOf(status); }
        catch (IllegalArgumentException exception) { return null; }
    }
}
