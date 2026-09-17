package com.comicatlas.api.exporter.application.service.impl;

import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.api.exporter.infrastructure.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.application.port.out.ExportPersistencePort;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
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

    @Transactional
    public void applyStarted(ExportTaskStartedEvent event) {
        ExportTask task = persistencePort.findTask(event.taskId());
        if (task == null || task.getStatus() == ExportTaskStatus.SUCCESS
                || task.getStatus() == ExportTaskStatus.FAILED) {
            return;
        }
        if (task.getStatus() == ExportTaskStatus.PENDING) {
            task.setStatus(ExportTaskStatus.RUNNING);
            persistencePort.updateTask(task);
        }
        updateItem(event.comicId(), ManagementTaskStatus.RUNNING, null, event.taskId());
    }

    @Transactional
    public void applyCompleted(ExportTaskCompletedEvent event) {
        ExportTask task = persistencePort.findTask(event.taskId());
        if (task == null || task.getStatus() == ExportTaskStatus.FAILED) {
            return;
        }
        if (task.getStatus() != ExportTaskStatus.SUCCESS) {
            task.setStatus(ExportTaskStatus.SUCCESS);
            task.setOutputRoot(event.outputRoot());
            task.setOutputPath(event.outputPath());
            task.setOutputSize(event.outputSize());
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            persistencePort.updateTask(task);
        }
        updateItem(event.comicId(), ManagementTaskStatus.SUCCEEDED, null, event.taskId());
    }

    @Transactional
    public void applyFailed(ExportTaskFailedEvent event) {
        ExportTask task = persistencePort.findTask(event.taskId());
        if (task == null || task.getStatus() == ExportTaskStatus.SUCCESS) {
            return;
        }
        if (task.getStatus() != ExportTaskStatus.FAILED) {
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMsg(event.errorMessage());
            task.setProgress(-1);
            persistencePort.updateTask(task);
        }
        updateItem(event.comicId(), ManagementTaskStatus.FAILED, event.errorMessage(), event.taskId());
}
    private void updateItem(Long comicId, ManagementTaskStatus status, String errorMessage, Long exportTaskId) {
        ManagementTaskItem item = managementTaskService.findActiveItem(TARGET_TYPE_COMIC, comicId, TaskType.EXPORT);
        if (item != null) {
            managementTaskService.updateItemStatus(item.getId(), status, errorMessage, RESULT_REF_TYPE, exportTaskId);
        }
    }
}
