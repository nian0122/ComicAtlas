package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.dto.RecoveryTaskVO;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.event.RecoveryEventPublisher;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.importer.service.RecoveryTaskService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryTaskServiceImpl implements RecoveryTaskService {

    private final RecoveryTaskMapper recoveryTaskMapper;
    private final RecoveryEventPublisher recoveryEventPublisher;
    private final ManagementTaskService managementTaskService;

    @Override
    @Transactional
    public RecoveryTaskVO createRecoveryTask() {
        // 检查是否有正在执行或等待中的任务
        long runningCount = recoveryTaskMapper.selectCount(
            new LambdaQueryWrapper<RecoveryTask>()
                .in(RecoveryTask::getStatus, "RUNNING", "QUEUED")
        );
        if (runningCount > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "已有恢复任务正在执行");
        }

        RecoveryTask task = new RecoveryTask();
        task.setStatus("QUEUED");
        task.setTotalComics(0);
        task.setRecoveredComics(0);
        task.setSkippedComics(0);
        task.setPlaceholderComics(0);
        task.setErrorComics(0);
        task.setRetryCount(0);
        recoveryTaskMapper.insert(task);

        // 同事务创建统一恢复任务并回填 management_task_id
        ManagementTaskResponse mgmtResp = createManagementTaskForRecovery(task.getId());
        task.setManagementTaskId(mgmtResp.getId());
        recoveryTaskMapper.updateById(task);

        Long taskId = task.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recoveryEventPublisher.publishRecoveryRequested(taskId);
                }
            });

        log.info("恢复任务创建: taskId={}", taskId);
        return toVO(task);
    }

    @Override
    public IPage<RecoveryTaskVO> listTasks(Integer page, Integer size) {
        var wrapper = new LambdaQueryWrapper<RecoveryTask>()
            .orderByDesc(RecoveryTask::getCreatedAt);
        var p = new Page<RecoveryTask>(page != null ? page : 1, size != null ? size : 20);
        return recoveryTaskMapper.selectPage(p, wrapper).convert(this::toVO);
    }

    @Override
    public RecoveryTaskVO getTaskDetail(Long id) {
        RecoveryTask recoveryTask = recoveryTaskMapper.selectById(id);
        if (recoveryTask == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        return toVO(recoveryTask);
    }

    @Override
    @Transactional
    public RecoveryTaskVO retryTask(Long id) {
        RecoveryTask recoveryTask = recoveryTaskMapper.selectById(id);
        if (recoveryTask == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        if (!"FAILED".equals(recoveryTask.getStatus())) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "仅 FAILED 状态可重试");
        }

        recoveryTask.setStatus("QUEUED");
        recoveryTask.setRetryCount(recoveryTask.getRetryCount() + 1);
        recoveryTask.setErrorMessage(null);
        recoveryTask.setStartedAt(null);
        recoveryTask.setEndedAt(null);
        recoveryTaskMapper.updateById(recoveryTask);

        // 同步统一任务：终态统一任务重置回 QUEUED（attempt 递增）
        if (recoveryTask.getManagementTaskId() != null) {
            try {
                managementTaskService.retryTask(recoveryTask.getManagementTaskId());
            } catch (com.comicatlas.api.common.exception.BusinessException e) {
                log.warn("统一恢复任务重试跳过（非终态）: managementTaskId={}, error={}",
                        recoveryTask.getManagementTaskId(), e.getMessage());
            }
        }

        Long taskId = recoveryTask.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recoveryEventPublisher.publishRecoveryRequested(taskId);
                }
            });

        log.info("恢复任务重试: taskId={}", taskId);
        return toVO(recoveryTask);
    }

    @Override
    @Transactional
    public void updateTask(RecoveryTaskVO vo) {
        RecoveryTask recoveryTask = recoveryTaskMapper.selectById(vo.getId());
        if (recoveryTask == null) { return; }

        if (vo.getStatus() != null) { recoveryTask.setStatus(vo.getStatus()); }
        if (vo.getTotalComics() != null) { recoveryTask.setTotalComics(vo.getTotalComics()); }
        if (vo.getRecoveredComics() != null) { recoveryTask.setRecoveredComics(vo.getRecoveredComics()); }
        if (vo.getSkippedComics() != null) { recoveryTask.setSkippedComics(vo.getSkippedComics()); }
        if (vo.getPlaceholderComics() != null) { recoveryTask.setPlaceholderComics(vo.getPlaceholderComics()); }
        if (vo.getErrorComics() != null) { recoveryTask.setErrorComics(vo.getErrorComics()); }
        if (vo.getErrorMessage() != null) { recoveryTask.setErrorMessage(vo.getErrorMessage()); }
        if (vo.getErrorDetails() != null) { recoveryTask.setErrorDetails(vo.getErrorDetails()); }
        if (vo.getStartedAt() != null) { recoveryTask.setStartedAt(vo.getStartedAt()); }
        if (vo.getEndedAt() != null) { recoveryTask.setEndedAt(vo.getEndedAt()); }

        recoveryTaskMapper.updateById(recoveryTask);
    }

    /**
     * 同事务创建统一恢复任务并返回其响应（target = SYSTEM:recoveryTaskId）。
     */
    private ManagementTaskResponse createManagementTaskForRecovery(Long recoveryTaskId) {
        CreateManagementTaskRequest mgmtReq = new CreateManagementTaskRequest();
        mgmtReq.setTaskType(TaskType.RECOVERY);
        mgmtReq.setOperation("存储恢复");
        mgmtReq.setTargetType("SYSTEM");
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("SYSTEM");
        target.setTargetId(recoveryTaskId);
        target.setOperationType(TaskType.RECOVERY);
        mgmtReq.setTargets(List.of(target));
        return managementTaskService.createTask(mgmtReq, null, null);
    }

    private RecoveryTaskVO toVO(RecoveryTask recoveryTask) {
        RecoveryTaskVO vo = new RecoveryTaskVO();
        vo.setId(recoveryTask.getId());
        vo.setStatus(recoveryTask.getStatus());
        vo.setTotalComics(recoveryTask.getTotalComics());
        vo.setRecoveredComics(recoveryTask.getRecoveredComics());
        vo.setSkippedComics(recoveryTask.getSkippedComics());
        vo.setPlaceholderComics(recoveryTask.getPlaceholderComics());
        vo.setErrorComics(recoveryTask.getErrorComics());
        vo.setErrorMessage(recoveryTask.getErrorMessage());
        vo.setErrorDetails(recoveryTask.getErrorDetails());
        vo.setRetryCount(recoveryTask.getRetryCount());
        vo.setCreatedAt(recoveryTask.getCreatedAt());
        vo.setStartedAt(recoveryTask.getStartedAt());
        vo.setEndedAt(recoveryTask.getEndedAt());
        return vo;
    }
}
