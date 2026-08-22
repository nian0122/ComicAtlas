package com.comicatlas.api.export.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ExportTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.PathTraversalException;
import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.export.service.ExportService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
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

    private final ComicMapper comicMapper;
    private final ExportTaskMapper exportTaskMapper;
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
        ExportTask task = createExportTaskRecord(comicId, normalizedFormat);

        Long taskId = task.getId();
        // 写入 Outbox（同事务），由 relay 异步发布，保证 DB 与消息一致
        outboxService.enqueue(new ExportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId,
                        normalizedFormat),
                MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导出任务创建: taskId={}, comicId={}", taskId, comicId);
        return toVO(task);
    }

    @Override
    public List<ExportTaskVO> listExports(Long comicId) {
        List<ExportTask> tasks = exportTaskMapper.selectList(new LambdaQueryWrapper<ExportTask>()
            .eq(ExportTask::getComicId, comicId)
            .orderByDesc(ExportTask::getCreatedAt));
        return tasks.stream().map(this::toVO).toList();
    }

    @Override
    public List<ExportTaskVO> listAllExports() {
        List<ExportTask> tasks = exportTaskMapper.selectList(new LambdaQueryWrapper<ExportTask>()
            .orderByDesc(ExportTask::getCreatedAt));
        return tasks.stream().map(this::toVO).toList();
    }

    @Override
    public ExportTaskVO getTask(Long taskId) {
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "导出任务不存在");
        }
        return toVO(task);
    }

    private void requireExportableComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        if (comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "漫画状态不允许导出，当前状态: " + comic.getStatus());
        }
    }

    private void rejectDuplicateActiveTask(Long comicId) {
        ExportTask existing = exportTaskMapper.selectOne(new LambdaQueryWrapper<ExportTask>()
            .eq(ExportTask::getComicId, comicId)
            .and(wrapper -> wrapper.eq(ExportTask::getStatus, ExportTaskStatus.PENDING).or().eq(ExportTask::getStatus, ExportTaskStatus.RUNNING)));
        if (existing != null) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已有进行中的导出任务，任务ID: " + existing.getId());
        }
    }

    private ExportTask createExportTaskRecord(Long comicId, String format) {
        ExportTask task = new ExportTask();
        task.setComicId(comicId);
        task.setFormat(format);
        task.setStatus(ExportTaskStatus.PENDING);
        task.setProgress(0);
        exportTaskMapper.insert(task);

        ManagementTaskResponse managementTaskResponse = createManagementTaskForExport(comicId);
        task.setManagementTaskId(managementTaskResponse.getId());
        exportTaskMapper.updateById(task);
        return task;
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

    private ExportTaskVO toVO(ExportTask task) {
        ExportTaskVO taskVO = new ExportTaskVO();
        taskVO.setId(task.getId());
        taskVO.setComicId(task.getComicId());
        taskVO.setFormat(task.getFormat() == null ? ExportFormats.ZIP : task.getFormat());
        taskVO.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        taskVO.setProgress(task.getProgress());
        taskVO.setOutputRoot(task.getOutputRoot());
        taskVO.setOutputPath(task.getOutputPath());
        taskVO.setOutputSize(task.getOutputSize());
        taskVO.setErrorMsg(task.getErrorMsg());
        taskVO.setCreatedAt(task.getCreatedAt());
        taskVO.setCompletedAt(task.getCompletedAt());

        // 计算物理路径：经逻辑存储根（默认 EXPORT）安全解析 outputPath，而非字符串拼接
        if (task.getOutputPath() != null && !task.getOutputPath().isBlank()) {
            String rootKey = task.getOutputRoot() != null && !task.getOutputRoot().isBlank()
                    ? task.getOutputRoot() : DEFAULT_OUTPUT_ROOT;
            try {
                taskVO.setPhysicalPath(storageProperties.root(rootKey).resolve(task.getOutputPath()).toString());
            } catch (PathTraversalException e) {
                log.warn("导出任务物理路径穿越被拒绝: taskId={}", task.getId());
                taskVO.setPhysicalPath(null);
            }
        }
        return taskVO;
    }
}
