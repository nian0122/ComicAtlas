package com.comicatlas.api.recovery.application.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.recovery.domain.model.RecoveryTaskStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.recovery.interfaces.rest.dto.RecoveryTaskVO;
import com.comicatlas.api.recovery.application.port.in.RecoveryTaskService;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.CreateCommand;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.RecoveryTaskSnapshot;
import com.comicatlas.api.recovery.application.port.out.RecoveryTaskPersistencePort.UpdateCommand;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.RecoveryRequestedEvent;
import com.comicatlas.api.task.domain.model.TaskType;
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

    private final RecoveryTaskPersistencePort persistencePort;
    private final OutboxService outboxService;
    private final ManagementTaskService managementTaskService;

    @Override
    @Transactional
    public RecoveryTaskVO createRecoveryTask() {
        rejectActiveTask();

        RecoveryTaskSnapshot task = createRecoveryTaskRecord();

        Long taskId = task.id();
        // 写入 Outbox（同事务），由 relay 异步发布，保证 DB 与消息一致
        outboxService.enqueue(new RecoveryRequestedEvent(UUID.randomUUID(), Instant.now(), taskId),
                MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_REQUESTED);

        log.info("恢复任务创建: taskId={}", taskId);
        return toVO(task);
    }

    @Override
    public IPage<RecoveryTaskVO> listTasks(Integer page, Integer size) {
        return persistencePort.findPage(page != null ? page : DEFAULT_PAGE,
                size != null ? size : DEFAULT_PAGE_SIZE).convert(this::toVO);
    }

    @Override
    public RecoveryTaskVO getTaskDetail(Long id) {
        RecoveryTaskSnapshot recoveryTask = persistencePort.findById(id);
        if (recoveryTask == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        return toVO(recoveryTask);
    }

    @Override
    @Transactional
    public RecoveryTaskVO retryTask(Long id) {
        RecoveryTaskSnapshot recoveryTask = persistencePort.findById(id);
        if (recoveryTask == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        if (recoveryTask.status() != RecoveryTaskStatus.FAILED) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "仅 FAILED 状态可重试");
        }

        int retryCount = recoveryTask.retryCount() + 1;
        persistencePort.update(new UpdateCommand(recoveryTask.id(), recoveryTask.managementTaskId(),
                RecoveryTaskStatus.QUEUED, recoveryTask.totalComics(), recoveryTask.recoveredComics(),
                recoveryTask.skippedComics(), recoveryTask.placeholderComics(), recoveryTask.errorComics(), null,
                recoveryTask.errorDetails(), retryCount, null, null));

        // 同步统一任务重置（仅状态，不在此重新入队——恢复事件由下方 Outbox 重发）
        if (recoveryTask.managementTaskId() != null) {
            managementTaskService.resetTaskState(recoveryTask.managementTaskId());
        }

        Long taskId = recoveryTask.id();
        // 写入 Outbox（同事务），由 relay 异步发布
        outboxService.enqueue(new RecoveryRequestedEvent(UUID.randomUUID(), Instant.now(), taskId),
                MqExchanges.RECOVERY, MqRoutingKeys.RECOVERY_REQUESTED);

        log.info("恢复任务重试: taskId={}", taskId);
        return toVO(new RecoveryTaskSnapshot(recoveryTask.id(), recoveryTask.managementTaskId(),
                RecoveryTaskStatus.QUEUED, recoveryTask.totalComics(), recoveryTask.recoveredComics(),
                recoveryTask.skippedComics(), recoveryTask.placeholderComics(), recoveryTask.errorComics(), null,
                recoveryTask.errorDetails(), retryCount, recoveryTask.createdAt(), null, null));
    }

    @Override
    @Transactional
    public void updateTask(RecoveryTaskVO taskVO) {
        RecoveryTaskSnapshot recoveryTask = persistencePort.findById(taskVO.getId());
        if (recoveryTask == null) {
            return;
        }

        persistencePort.update(new UpdateCommand(recoveryTask.id(), recoveryTask.managementTaskId(),
                taskVO.getStatus() == null ? recoveryTask.status() : fromName(taskVO.getStatus()),
                valueOrDefault(taskVO.getTotalComics(), recoveryTask.totalComics()),
                valueOrDefault(taskVO.getRecoveredComics(), recoveryTask.recoveredComics()),
                valueOrDefault(taskVO.getSkippedComics(), recoveryTask.skippedComics()),
                valueOrDefault(taskVO.getPlaceholderComics(), recoveryTask.placeholderComics()),
                valueOrDefault(taskVO.getErrorComics(), recoveryTask.errorComics()),
                taskVO.getErrorMessage() == null ? recoveryTask.errorMessage() : taskVO.getErrorMessage(),
                taskVO.getErrorDetails() == null ? recoveryTask.errorDetails() : taskVO.getErrorDetails(),
                recoveryTask.retryCount(),
                taskVO.getStartedAt() == null ? recoveryTask.startedAt() : taskVO.getStartedAt(),
                taskVO.getEndedAt() == null ? recoveryTask.endedAt() : taskVO.getEndedAt()));
    }

    private void rejectActiveTask() {
        long runningCount = persistencePort.countActiveTasks();
        if (runningCount > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "已有恢复任务正在执行");
        }
    }

    private RecoveryTaskSnapshot createRecoveryTaskRecord() {
        Long taskId = persistencePort.insert(new CreateCommand(RecoveryTaskStatus.QUEUED, 0, 0, 0, 0, 0, 0));

        ManagementTaskResponse mgmtResp = createManagementTaskForRecovery(taskId);
        persistencePort.update(new UpdateCommand(taskId, mgmtResp.getId(), RecoveryTaskStatus.QUEUED,
                0, 0, 0, 0, 0, null, null, 0, null, null));
        return new RecoveryTaskSnapshot(taskId, mgmtResp.getId(), RecoveryTaskStatus.QUEUED,
                0, 0, 0, 0, 0, null, null, 0, null, null, null);
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

    private RecoveryTaskVO toVO(RecoveryTaskSnapshot recoveryTask) {
        RecoveryTaskVO taskVO = new RecoveryTaskVO();
        taskVO.setId(recoveryTask.id());
        taskVO.setStatus(recoveryTask.status() == null ? null : recoveryTask.status().name());
        taskVO.setTotalComics(recoveryTask.totalComics()); taskVO.setRecoveredComics(recoveryTask.recoveredComics());
        taskVO.setSkippedComics(recoveryTask.skippedComics()); taskVO.setPlaceholderComics(recoveryTask.placeholderComics());
        taskVO.setErrorComics(recoveryTask.errorComics()); taskVO.setErrorMessage(recoveryTask.errorMessage());
        taskVO.setErrorDetails(recoveryTask.errorDetails()); taskVO.setRetryCount(recoveryTask.retryCount());
        taskVO.setCreatedAt(recoveryTask.createdAt()); taskVO.setStartedAt(recoveryTask.startedAt());
        taskVO.setEndedAt(recoveryTask.endedAt());
        return taskVO;
    }

    private static Integer valueOrDefault(Integer value, Integer fallback) {
        return value == null ? fallback : value;
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
