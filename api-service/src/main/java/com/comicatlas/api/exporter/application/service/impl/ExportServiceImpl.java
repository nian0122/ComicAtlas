package com.comicatlas.api.exporter.application.service.impl;

import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.storage.PathTraversalException;
import com.comicatlas.api.exporter.interfaces.rest.dto.ExportTaskVO;
import com.comicatlas.api.exporter.application.port.in.ExportService;
import com.comicatlas.api.exporter.application.port.out.ExportPersistencePort;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private static final String DEFAULT_OUTPUT_ROOT = "EXPORT";
    /** 管理任务目标类型：漫画 */
    private static final String TARGET_TYPE_COMIC = "COMIC";
    /** 管理任务操作描述 */
    private static final String EXPORT_OPERATION = "导出漫画";

    private final ExportPersistencePort persistencePort;
    private final OutboxService outboxService;
    private final ManagementTaskService managementTaskService;
    private final ApiStorageProperties storageProperties;

    @Override
    @Transactional
    public ExportTaskVO createExportTask(Long comicId) {
        return createExportTask(comicId, ExportFormats.ZIP);
    }

    @Override
    @Transactional
    public ExportTaskVO createExportTask(Long comicId, String format) {
        requireExportableComic(comicId);
        rejectDuplicateActiveTask(comicId);

        String normalizedFormat = normalizeFormat(format);
        ExportPersistencePort.ExportTaskSnapshot task = createExportTaskRecord(comicId, normalizedFormat);

        Long taskId = task.id();
        // 写入 Outbox（同事务），由 relay 异步发布，保证 DB 与消息一致
        outboxService.enqueue(new ExportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId,
                        normalizedFormat),
                MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导出任务创建: taskId={}, comicId={}", taskId, comicId);
        return toVO(task);
    }

    @Override
    public List<ExportTaskVO> listExports(Long comicId) {
        List<ExportPersistencePort.ExportTaskSnapshot> tasks = persistencePort.findTasksByComicId(comicId);
        return tasks.stream().map(this::toVO).toList();
    }

    @Override
    public List<ExportTaskVO> listAllExports() {
        List<ExportPersistencePort.ExportTaskSnapshot> tasks = persistencePort.findAllTasks();
        return tasks.stream().map(this::toVO).toList();
    }

    @Override
    public ExportTaskVO getTask(Long taskId) {
        ExportPersistencePort.ExportTaskSnapshot task = persistencePort.findTask(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "导出任务不存在");
        }
        return toVO(task);
    }

    private void requireExportableComic(Long comicId) {
        ExportPersistencePort.ComicSnapshot comic = persistencePort.findComic(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        if (comic.status() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "漫画状态不允许导出，当前状态: " + comic.status());
        }
    }

    private void rejectDuplicateActiveTask(Long comicId) {
        ExportPersistencePort.ExportTaskSnapshot existing = persistencePort.findActiveTask(comicId);
        if (existing != null) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已有进行中的导出任务，任务ID: " + existing.id());
        }
    }

    private ExportPersistencePort.ExportTaskSnapshot createExportTaskRecord(Long comicId, String format) {
        Long taskId = persistencePort.insertTask(new ExportPersistencePort.CreateTaskCommand(
                comicId, format, ExportTaskStatus.PENDING, 0));

        ManagementTaskResponse managementTaskResponse = createManagementTaskForExport(comicId);
        persistencePort.updateTask(new ExportPersistencePort.UpdateTaskCommand(taskId,
                managementTaskResponse.getId(), ExportTaskStatus.PENDING, 0, null, null, null, null, null));
        return new ExportPersistencePort.ExportTaskSnapshot(taskId, managementTaskResponse.getId(), comicId,
                format, ExportTaskStatus.PENDING, 0, null, null, null, null, null, null);
    }

    private static String normalizeFormat(String format) {
        String normalized = format == null || format.isBlank()
                ? ExportFormats.ZIP : format.trim().toUpperCase(Locale.ROOT);
        if (!ExportFormats.ZIP.equals(normalized) && !ExportFormats.CBZ.equals(normalized)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的导出格式: " + format);
        }
        return normalized;
    }

    /**
     * 同事务创建统一导出任务并返回其响应。
     */
    private ManagementTaskResponse createManagementTaskForExport(Long comicId) {
        CreateManagementTaskRequest mgmtReq = new CreateManagementTaskRequest();
        mgmtReq.setTaskType(TaskType.EXPORT);
        mgmtReq.setOperation(EXPORT_OPERATION);
        mgmtReq.setTargetType(TARGET_TYPE_COMIC);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(TARGET_TYPE_COMIC);
        target.setTargetId(comicId);
        target.setOperationType(TaskType.EXPORT);
        mgmtReq.setTargets(List.of(target));
        return managementTaskService.createTask(mgmtReq, null, null);
    }

    private ExportTaskVO toVO(ExportPersistencePort.ExportTaskSnapshot task) {
        ExportTaskVO taskVO = new ExportTaskVO();
        taskVO.setId(task.id()); taskVO.setComicId(task.comicId());
        taskVO.setFormat(task.format() == null ? ExportFormats.ZIP : task.format());
        taskVO.setStatus(task.status() == null ? null : task.status().name());
        taskVO.setProgress(task.progress()); taskVO.setOutputRoot(task.outputRoot());
        taskVO.setOutputPath(task.outputPath()); taskVO.setOutputSize(task.outputSize());
        taskVO.setErrorMsg(task.errorMsg()); taskVO.setCreatedAt(task.createdAt());
        taskVO.setCompletedAt(task.completedAt());

        // 计算物理路径：经逻辑存储根（默认 EXPORT）安全解析 outputPath，而非字符串拼接
        if (task.outputPath() != null && !task.outputPath().isBlank()) {
            String rootKey = task.outputRoot() != null && !task.outputRoot().isBlank()
                    ? task.outputRoot() : DEFAULT_OUTPUT_ROOT;
            try {
                taskVO.setPhysicalPath(storageProperties.root(rootKey).resolve(task.outputPath()).toString());
            } catch (PathTraversalException e) {
                log.warn("导出任务物理路径穿越被拒绝: taskId={}", task.id());
                taskVO.setPhysicalPath(null);
            }
        }
        return taskVO;
    }
}
