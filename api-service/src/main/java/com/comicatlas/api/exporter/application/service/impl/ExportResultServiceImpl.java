package com.comicatlas.api.exporter.application.service.impl;

import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.api.exporter.application.port.out.ExportPersistencePort;
import com.comicatlas.api.exporter.application.port.out.ExportManagementTaskQueryPort;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 导出结果应用服务，统一处理导出表与管理任务项的状态联动。 */
@Service
@RequiredArgsConstructor
public class ExportResultServiceImpl implements com.comicatlas.api.exporter.application.port.in.ExportResultService {
    // 导出结果契约由应用服务公开，具体实现保持在导出业务包内。
    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String RESULT_REF_TYPE = "EXPORT_TASK";
    private final ExportPersistencePort persistencePort;
    private final ManagementTaskService managementTaskService;
    private final ExportManagementTaskQueryPort managementTaskQueryPort;

    @Transactional
    public void applyStarted(ExportTaskStartedEvent event) {
        ExportPersistencePort.ExportTaskSnapshot task = persistencePort.findTask(event.taskId());
        if (task == null || task.status() == ExportTaskStatus.SUCCESS
                || task.status() == ExportTaskStatus.FAILED) {
            return;
        }
        if (task.status() == ExportTaskStatus.PENDING) {
            persistencePort.updateTask(update(task, ExportTaskStatus.RUNNING, task.progress(),
                    null, null, null, null, null));
        }
        updateItem(event.comicId(), ManagementTaskStatus.RUNNING, null, event.taskId());
    }

    @Transactional
    public void applyCompleted(ExportTaskCompletedEvent event) {
        ExportPersistencePort.ExportTaskSnapshot task = persistencePort.findTask(event.taskId());
        if (task == null || task.status() == ExportTaskStatus.FAILED) {
            return;
        }
        if (task.status() != ExportTaskStatus.SUCCESS) {
            persistencePort.updateTask(update(task, ExportTaskStatus.SUCCESS, 100,
                    event.outputRoot(), event.outputPath(), event.outputSize(), null, LocalDateTime.now()));
        }
        updateItem(event.comicId(), ManagementTaskStatus.SUCCEEDED, null, event.taskId());
    }

    @Transactional
    public void applyFailed(ExportTaskFailedEvent event) {
        ExportPersistencePort.ExportTaskSnapshot task = persistencePort.findTask(event.taskId());
        if (task == null || task.status() == ExportTaskStatus.SUCCESS) {
            return;
        }
        if (task.status() != ExportTaskStatus.FAILED) {
            persistencePort.updateTask(update(task, ExportTaskStatus.FAILED, -1,
                    null, null, null, event.errorMessage(), LocalDateTime.now()));
        }
        updateItem(event.comicId(), ManagementTaskStatus.FAILED, event.errorMessage(), event.taskId());
}
    private void updateItem(Long comicId, ManagementTaskStatus status, String errorMessage, Long exportTaskId) {
        ExportManagementTaskQueryPort.ItemSnapshot item = managementTaskQueryPort.findActiveItem(
                TARGET_TYPE_COMIC, comicId, TaskType.EXPORT);
        if (item != null) {
            managementTaskService.updateItemStatus(item.id(), status, errorMessage, RESULT_REF_TYPE, exportTaskId);
        }
    }

    private ExportPersistencePort.UpdateTaskCommand update(
            ExportPersistencePort.ExportTaskSnapshot task, ExportTaskStatus status, Integer progress,
            String outputRoot, String outputPath, Long outputSize, String errorMessage,
            LocalDateTime completedAt) {
        return new ExportPersistencePort.UpdateTaskCommand(task.id(), task.managementTaskId(), status, progress,
                outputRoot == null ? task.outputRoot() : outputRoot,
                outputPath == null ? task.outputPath() : outputPath,
                outputSize == null ? task.outputSize() : outputSize,
                errorMessage == null ? task.errorMsg() : errorMessage, completedAt);
    }
}
