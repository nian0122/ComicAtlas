package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.RecoveryTaskStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.importer.dto.RecoveryTaskVO;
import com.comicatlas.api.importer.entity.RecoveryTask;
import com.comicatlas.api.importer.mapper.RecoveryTaskMapper;
import com.comicatlas.api.importer.service.RecoveryTaskService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.RecoveryRequestedEvent;
import com.comicatlas.api.common.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryTaskServiceImpl implements RecoveryTaskService {

    /** 管理任务目标类型：系统级任务 */
    private static final String TARGET_TYPE_SYSTEM = "SYSTEM";
    /** 管理任务操作描述 */
    private static final String RECOVERY_OPERATION = "存储恢复";
    /** 分页默认页码 */
    private static final int DEFAULT_PAGE = 1;
    /** 分页默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final RecoveryTaskMapper recoveryTaskMapper;
    private final OutboxService outboxService;
    private final ManagementTaskService managementTaskService;

    @Override
    @Transactional
    public RecoveryTaskVO createRecoveryTask() {
        rejectActiveTask();

        RecoveryTask task = createRecoveryTaskRecord();

        Long taskId = task.getId();
        // 写入 Outbox（同事务），由 relay 异步发布，保证 DB 与消息一致
        outboxService.enqueue(new RecoveryRequestedEvent(UUID.randomUUID(), Instant.now(), taskId),
                MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_REQUESTED);

        log.info("恢复任务创建: taskId={}", taskId);
        return toVO(task);
    }

    @Override
    public IPage<RecoveryTaskVO> listTasks(Integer page, Integer size) {
        LambdaQueryWrapper<RecoveryTask> wrapper = new LambdaQueryWrapper<RecoveryTask>()
            .orderByDesc(RecoveryTask::getCreatedAt);
        Page<RecoveryTask> pageRequest = new Page<>(
            page != null ? page : DEFAULT_PAGE,
            size != null ? size : DEFAULT_PAGE_SIZE);
        return recoveryTaskMapper.selectPage(pageRequest, wrapper).convert(this::toVO);
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
        if (recoveryTask.getStatus() != RecoveryTaskStatus.FAILED) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "仅 FAILED 状态可重试");
        }

        recoveryTask.setStatus(RecoveryTaskStatus.QUEUED);
        recoveryTask.setRetryCount(recoveryTask.getRetryCount() + 1);
        recoveryTask.setErrorMessage(null);
        recoveryTask.setStartedAt(null);
        recoveryTask.setEndedAt(null);
        recoveryTaskMapper.updateById(recoveryTask);

        // 同步统一任务重置（仅状态，不在此重新入队——恢复事件由下方 Outbox 重发）
        if (recoveryTask.getManagementTaskId() != null) {
            managementTaskService.resetTaskState(recoveryTask.getManagementTaskId());
        }

        Long taskId = recoveryTask.getId();
        // 写入 Outbox（同事务），由 relay 异步发布
        outboxService.enqueue(new RecoveryRequestedEvent(UUID.randomUUID(), Instant.now(), taskId),
                MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_REQUESTED);

        log.info("恢复任务重试: taskId={}", taskId);
        return toVO(recoveryTask);
    }

    @Override
    @Transactional
    public void updateTask(RecoveryTaskVO taskVO) {
        RecoveryTask recoveryTask = recoveryTaskMapper.selectById(taskVO.getId());
        if (recoveryTask == null) {
            return;
        }

        if (taskVO.getStatus() != null) {
            recoveryTask.setStatus(fromName(taskVO.getStatus()));
        }
        if (taskVO.getTotalComics() != null) { recoveryTask.setTotalComics(taskVO.getTotalComics()); }
        if (taskVO.getRecoveredComics() != null) { recoveryTask.setRecoveredComics(taskVO.getRecoveredComics()); }
        if (taskVO.getSkippedComics() != null) { recoveryTask.setSkippedComics(taskVO.getSkippedComics()); }
        if (taskVO.getPlaceholderComics() != null) { recoveryTask.setPlaceholderComics(taskVO.getPlaceholderComics()); }
        if (taskVO.getErrorComics() != null) { recoveryTask.setErrorComics(taskVO.getErrorComics()); }
        if (taskVO.getErrorMessage() != null) { recoveryTask.setErrorMessage(taskVO.getErrorMessage()); }
        if (taskVO.getErrorDetails() != null) { recoveryTask.setErrorDetails(taskVO.getErrorDetails()); }
        if (taskVO.getStartedAt() != null) { recoveryTask.setStartedAt(taskVO.getStartedAt()); }
        if (taskVO.getEndedAt() != null) { recoveryTask.setEndedAt(taskVO.getEndedAt()); }

        recoveryTaskMapper.updateById(recoveryTask);
    }

    private void rejectActiveTask() {
        long runningCount = recoveryTaskMapper.selectCount(
            new LambdaQueryWrapper<RecoveryTask>()
                .in(RecoveryTask::getStatus, RecoveryTaskStatus.RUNNING, RecoveryTaskStatus.QUEUED)
        );
        if (runningCount > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "已有恢复任务正在执行");
        }
    }

    private RecoveryTask createRecoveryTaskRecord() {
        RecoveryTask task = new RecoveryTask();
        task.setStatus(RecoveryTaskStatus.QUEUED);
        task.setTotalComics(0);
        task.setRecoveredComics(0);
        task.setSkippedComics(0);
        task.setPlaceholderComics(0);
        task.setErrorComics(0);
        task.setRetryCount(0);
        recoveryTaskMapper.insert(task);

        ManagementTaskResponse mgmtResp = createManagementTaskForRecovery(task.getId());
        task.setManagementTaskId(mgmtResp.getId());
        recoveryTaskMapper.updateById(task);
        return task;
    }

    /**
     * 同事务创建统一恢复任务并返回其响应（target = SYSTEM:recoveryTaskId）。
     */
    private ManagementTaskResponse createManagementTaskForRecovery(Long recoveryTaskId) {
        CreateManagementTaskRequest managementTaskRequest = new CreateManagementTaskRequest();
        managementTaskRequest.setTaskType(TaskType.RECOVERY);
        managementTaskRequest.setOperation(RECOVERY_OPERATION);
        managementTaskRequest.setTargetType(TARGET_TYPE_SYSTEM);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(TARGET_TYPE_SYSTEM);
        target.setTargetId(recoveryTaskId);
        target.setOperationType(TaskType.RECOVERY);
        managementTaskRequest.setTargets(List.of(target));
        return managementTaskService.createTask(managementTaskRequest, null, null);
    }

    private RecoveryTaskVO toVO(RecoveryTask recoveryTask) {
        RecoveryTaskVO taskVO = new RecoveryTaskVO();
        taskVO.setId(recoveryTask.getId());
        taskVO.setStatus(recoveryTask.getStatus() == null ? null : recoveryTask.getStatus().name());
        taskVO.setTotalComics(recoveryTask.getTotalComics());
        taskVO.setRecoveredComics(recoveryTask.getRecoveredComics());
        taskVO.setSkippedComics(recoveryTask.getSkippedComics());
        taskVO.setPlaceholderComics(recoveryTask.getPlaceholderComics());
        taskVO.setErrorComics(recoveryTask.getErrorComics());
        taskVO.setErrorMessage(recoveryTask.getErrorMessage());
        taskVO.setErrorDetails(recoveryTask.getErrorDetails());
        taskVO.setRetryCount(recoveryTask.getRetryCount());
        taskVO.setCreatedAt(recoveryTask.getCreatedAt());
        taskVO.setStartedAt(recoveryTask.getStartedAt());
        taskVO.setEndedAt(recoveryTask.getEndedAt());
        return taskVO;
    }

    /**
     * 状态名安全转枚举，非法值返回 null（updateTask 为事件回传路径，容忍未知状态名）。
     */
    private static RecoveryTaskStatus fromName(String statusName) {
        if (statusName == null) {
            return null;
        }
        for (RecoveryTaskStatus status : RecoveryTaskStatus.values()) {
            if (status.name().equals(statusName)) {
                return status;
            }
        }
        return null;
    }
}
