package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
            throw new BusinessException(409, "已有恢复任务正在执行");
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
        RecoveryTask t = recoveryTaskMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(404, "任务不存在");
        }
        return toVO(t);
    }

    @Override
    @Transactional
    public RecoveryTaskVO retryTask(Long id) {
        RecoveryTask t = recoveryTaskMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (!"FAILED".equals(t.getStatus())) {
            throw new BusinessException(400, "仅 FAILED 状态可重试");
        }

        t.setStatus("QUEUED");
        t.setRetryCount(t.getRetryCount() + 1);
        t.setErrorMessage(null);
        t.setStartedAt(null);
        t.setEndedAt(null);
        recoveryTaskMapper.updateById(t);

        // 同步统一任务：终态统一任务重置回 QUEUED（attempt 递增）
        if (t.getManagementTaskId() != null) {
            try {
                managementTaskService.retryTask(t.getManagementTaskId());
            } catch (com.comicatlas.api.common.exception.BusinessException e) {
                log.warn("统一恢复任务重试跳过（非终态）: managementTaskId={}, error={}",
                        t.getManagementTaskId(), e.getMessage());
            }
        }

        Long taskId = t.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recoveryEventPublisher.publishRecoveryRequested(taskId);
                }
            });

        log.info("恢复任务重试: taskId={}", taskId);
        return toVO(t);
    }

    @Override
    @Transactional
    public void updateTask(RecoveryTaskVO vo) {
        RecoveryTask t = recoveryTaskMapper.selectById(vo.getId());
        if (t == null) return;

        if (vo.getStatus() != null) t.setStatus(vo.getStatus());
        if (vo.getTotalComics() != null) t.setTotalComics(vo.getTotalComics());
        if (vo.getRecoveredComics() != null) t.setRecoveredComics(vo.getRecoveredComics());
        if (vo.getSkippedComics() != null) t.setSkippedComics(vo.getSkippedComics());
        if (vo.getPlaceholderComics() != null) t.setPlaceholderComics(vo.getPlaceholderComics());
        if (vo.getErrorComics() != null) t.setErrorComics(vo.getErrorComics());
        if (vo.getErrorMessage() != null) t.setErrorMessage(vo.getErrorMessage());
        if (vo.getErrorDetails() != null) t.setErrorDetails(vo.getErrorDetails());
        if (vo.getStartedAt() != null) t.setStartedAt(vo.getStartedAt());
        if (vo.getEndedAt() != null) t.setEndedAt(vo.getEndedAt());

        recoveryTaskMapper.updateById(t);
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

    private RecoveryTaskVO toVO(RecoveryTask t) {
        RecoveryTaskVO vo = new RecoveryTaskVO();
        vo.setId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setTotalComics(t.getTotalComics());
        vo.setRecoveredComics(t.getRecoveredComics());
        vo.setSkippedComics(t.getSkippedComics());
        vo.setPlaceholderComics(t.getPlaceholderComics());
        vo.setErrorComics(t.getErrorComics());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setErrorDetails(t.getErrorDetails());
        vo.setRetryCount(t.getRetryCount());
        vo.setCreatedAt(t.getCreatedAt());
        vo.setStartedAt(t.getStartedAt());
        vo.setEndedAt(t.getEndedAt());
        return vo;
    }
}
