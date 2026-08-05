package com.comicatlas.api.export.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.event.ExportEventPublisher;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.export.service.ExportService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private final ComicMapper comicMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final ExportEventPublisher eventPublisher;
    private final ManagementTaskService managementTaskService;

    @Value("${export.output-dir}")
    private String exportDir;

    @Override
    @Transactional
    public ExportTaskVO createExportTask(Long comicId) {
        // 1. 校验漫画存在且状态为 READY
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        if (comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "漫画状态不允许导出，当前状态: " + comic.getStatus());
        }

        // 2. 幂等检查：已存在 PENDING/RUNNING 的导出任务则拒绝
        var existing = exportTaskMapper.selectOne(new LambdaQueryWrapper<ExportTask>()
            .eq(ExportTask::getComicId, comicId)
            .and(w -> w.eq(ExportTask::getStatus, "PENDING").or().eq(ExportTask::getStatus, "RUNNING")));
        if (existing != null) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已有进行中的导出任务，任务ID: " + existing.getId());
        }

        // 3. 创建 export_task
        ExportTask task = new ExportTask();
        task.setComicId(comicId);
        task.setStatus("PENDING");
        task.setProgress(0);
        exportTaskMapper.insert(task);

        // 3.5 同事务创建统一管理任务并回填 management_task_id
        ManagementTaskResponse mgmtResp = createManagementTaskForExport(comicId);
        task.setManagementTaskId(mgmtResp.getId());
        exportTaskMapper.updateById(task);

        // 4. 事务提交后发送 MQ
        Long taskId = task.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishExportTaskCreated(taskId, comicId);
                }
            });

        log.info("导出任务创建: taskId={}, comicId={}", taskId, comicId);
        return toVO(task);
    }

    @Override
    public List<ExportTaskVO> listExports(Long comicId) {
        var list = exportTaskMapper.selectList(new LambdaQueryWrapper<ExportTask>()
            .eq(ExportTask::getComicId, comicId)
            .orderByDesc(ExportTask::getCreatedAt));
        return list.stream().map(this::toVO).toList();
    }

    @Override
    public ExportTaskVO getTask(Long taskId) {
        ExportTask task = exportTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "导出任务不存在");
        }
        return toVO(task);
    }

    /**
     * 同事务创建统一导出任务并返回其响应。
     */
    private ManagementTaskResponse createManagementTaskForExport(Long comicId) {
        CreateManagementTaskRequest mgmtReq = new CreateManagementTaskRequest();
        mgmtReq.setTaskType(com.comicatlas.common.enums.TaskType.EXPORT);
        mgmtReq.setOperation("导出漫画");
        mgmtReq.setTargetType("COMIC");
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("COMIC");
        target.setTargetId(comicId);
        target.setOperationType(com.comicatlas.common.enums.TaskType.EXPORT);
        mgmtReq.setTargets(List.of(target));
        return managementTaskService.createTask(mgmtReq, null, null);
    }

    private ExportTaskVO toVO(ExportTask task) {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(task.getId());
        vo.setComicId(task.getComicId());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setOutputRoot(task.getOutputRoot());
        vo.setOutputPath(task.getOutputPath());
        vo.setOutputSize(task.getOutputSize());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setCompletedAt(task.getCompletedAt());

        // 计算物理路径: exportDir + "/" + outputPath
        if (task.getOutputPath() != null && !task.getOutputPath().isBlank()) {
            String root = task.getOutputRoot() != null && !task.getOutputRoot().isBlank()
                ? task.getOutputRoot() : exportDir;
            vo.setPhysicalPath(root.replace("\\", "/") + "/" + task.getOutputPath());
        }
        return vo;
    }
}
