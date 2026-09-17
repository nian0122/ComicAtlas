package com.comicatlas.api.task.application.service.impl;

import com.comicatlas.api.shared.application.model.PageResult;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.application.port.out.TaskRetryPublisher;
import com.comicatlas.api.task.application.assembler.TaskResponseAssembler;
import com.comicatlas.api.task.application.port.in.TaskQueryService;
import com.comicatlas.api.task.application.service.TaskAggregationService;
import com.comicatlas.api.task.application.service.TaskInternalQueryService;
import com.comicatlas.api.task.application.port.out.TaskLifecyclePolicy;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.TaskSnapshot;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.ItemSnapshot;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.CreateTaskCommand;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.CreateItemCommand;
import com.comicatlas.api.task.application.port.out.TaskQueryPersistencePort.UpdateTaskCommand;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskStage;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.domain.model.TaskLockKey;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.shared.crypto.DigestService;
import com.comicatlas.api.shared.monitoring.MonitoredOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一管理任务服务。
 * <p>
 * 提供任务创建（幂等/目标锁）、分页查询、详情、cancel、retry、item 状态更新。
 * 不塞具体业务 payload，业务扩展通过 management_task_id 一对一引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@MonitoredOperation("management-task")
public class ManagementTaskServiceImpl implements ManagementTaskService {
    // 管理任务应用契约由 Controller/事件适配器固定，具体实现保持在任务业务包内。

    /** 初始 attempt 次数。 */
    private static final int INITIAL_ATTEMPT = 1;
    private static final List<ManagementTaskStatus> TERMINAL_ITEM_STATUSES = List.of(
            ManagementTaskStatus.CANCELLED,
            ManagementTaskStatus.SUCCEEDED,
            ManagementTaskStatus.PARTIALLY_SUCCEEDED,
            ManagementTaskStatus.FAILED);
    private final DigestService digestService;

    private final TaskQueryPersistencePort persistencePort;
    private final TaskRetryPublisher taskRetryPublisher;
    private final TaskResponseAssembler taskResponseAssembler;
    private final TaskQueryService taskQueryService;
    private final TaskAggregationService taskAggregationService;
    private final TaskInternalQueryService taskInternalQueryService;
    private final TaskLifecyclePolicy taskLifecyclePolicy;

    // ======================== 创建任务 ========================

    /**
     * 创建管理任务（支持 Idempotency-Key）。
     *
     * @param request        创建请求
     * @param idempotencyKey 幂等键（可选）
     * @param payload        原始请求 payload 用于幂等校验（JSON 字符串）
     * @return 创建的任务响应
     */
    @Transactional
    public ManagementTaskResponse createTask(CreateManagementTaskRequest request,
                                              String idempotencyKey,
                                              String payload) {
        // 幂等检查
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            TaskSnapshot existing = persistencePort.findByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                String expectedHash = digestService.sha256(payload);
                if (expectedHash.equals(existing.idempotencyPayloadHash())) {
                    log.info("幂等命中 idempotencyKey={}, 返回已有任务 {}", idempotencyKey, existing.id());
                    return taskResponseAssembler.toResponse(existing);
                }
                throw new ConflictException("幂等键 " + idempotencyKey + " 已存在但 payload 不匹配");
            }
        }

        // 构建主任务
        int totalCount = 0;
        if (request.getTargets() != null) {
            totalCount = request.getTargets().size();
        }
        String payloadHash = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : digestService.sha256(payload);
        Long taskId = persistencePort.insertTask(new CreateTaskCommand(request.getTaskType(), request.getOperation(),
                request.getTargetType(), request.getBatchId(), request.getTargets() != null
                && request.getTargets().size() > 1, ManagementTaskStatus.QUEUED, 0, INITIAL_ATTEMPT,
                idempotencyKey, payloadHash, totalCount, 0, 0, 0));

        // 元数据刷新可能展开为多个章节项；同一本漫画只允许执行一次 READY→REFRESHING CAS。
        taskLifecyclePolicy.lockOnCreate(request.getTaskType(), request.getTargets().stream()
                .map(target -> new TaskLifecyclePolicy.TaskTarget(target.getTargetType(), target.getTargetId(),
                        target.getOperationType()))
                .toList());

        // 创建目标项（带目标冲突锁检查）
        int itemCount = 0;
        if (request.getTargets() != null) {
            for (CreateManagementTaskRequest.TaskTarget target : request.getTargets()) {
                TaskType opType = target.getOperationType() != null
                        ? target.getOperationType()
                        : request.getTaskType();

                String lockKey = TaskLockKey.of(
                        target.getTargetType(), target.getTargetId(), opType);

                // 检查目标冲突锁：查询是否有活跃项占用此 lock_key
            Long activeCount = persistencePort.countItemsByLockKey(lockKey);
                if (activeCount > 0) {
                    throw new ConflictException(
                            String.format("目标 %s:%d 在操作 %s 中已有活跃任务项，无法创建新任务",
                                    target.getTargetType(), target.getTargetId(), opType));
                }

                try {
                    persistencePort.insertTaskItem(new CreateItemCommand(taskId, target.getTargetType(),
                            target.getTargetId(), opType, ManagementTaskStatus.QUEUED, INITIAL_ATTEMPT, 0, lockKey));
                    itemCount++;
                } catch (DuplicateKeyException ex) {
                    throw new ConflictException(String.format("目标 %s:%d 在操作 %s 中已有活跃任务项",
                            target.getTargetType(), target.getTargetId(), opType));
                }
            }
        }

        log.info("创建管理任务 id={}, type={}, items={}, idempotencyKey={}",
                taskId, request.getTaskType(), itemCount, idempotencyKey);
        return taskResponseAssembler.toResponse(persistencePort.findTask(taskId));
    }


    // ======================== 查询 ========================

    /** 分页查询任务列表。 */
    public PageResult<ManagementTaskResponse> listTasks(int page, int size, TaskType type,
                                                    ManagementTaskStatus status, String batchId,
                                                    String targetType, Long targetId) {
        return taskQueryService.listTasks(page, size, type, status, batchId, targetType, targetId);
    }

    /** 查询任务详情。 */
    public ManagementTaskResponse getTask(Long taskId) {
        return taskQueryService.getTask(taskId);
    }

    /** 查询任务项。 */
    public List<ManagementTaskItemResponse> getTaskItems(Long taskId) {
        return taskQueryService.getTaskItems(taskId);
    }

    // ======================== Cancel ========================

    /**
     * 取消任务。
     * 仅 QUEUED/RUNNING 状态可取消。
     */
    @Transactional
    public ManagementTaskResponse cancelTask(Long taskId) {
        TaskSnapshot task = persistencePort.findTask(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }

        if (task.status() == ManagementTaskStatus.CANCELLED) {
            return taskResponseAssembler.toResponse(task);
        }

        if (task.status().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 已处于终态 " + task.status() + "，无法取消");
        }

        // 如果已经在取消中，不重复操作
        if (task.status() == ManagementTaskStatus.CANCELLING) {
            return taskResponseAssembler.toResponse(task);
        }

        LocalDateTime updateTime = LocalDateTime.now();
        persistencePort.updateTask(new UpdateTaskCommand(taskId, ManagementTaskStatus.CANCELLING, updateTime));

        // 将未开始的 item 标记为 CANCELLED
        LocalDateTime cancelTime = LocalDateTime.now();
        persistencePort.cancelQueuedItems(taskId, cancelTime, cancelTime);

        // 重新聚合状态
        taskAggregationService.aggregate(taskId);
        List<ItemSnapshot> cancelledItems = persistencePort.findItemsByTaskId(taskId);
        taskLifecyclePolicy.releaseAfterCancel(task.taskType(), taskInternalQueryService.countActiveItems(taskId),
                cancelledItems.stream().map(item -> new TaskLifecyclePolicy.TaskItemReference(item.targetType(),
                        item.targetId(), item.operationType(), item.status())).toList());

        return taskResponseAssembler.toResponse(persistencePort.findTask(taskId));
    }

    // ======================== Retry ========================

    /**
     * 重试任务。
     * 仅终态（FAILED/PARTIALLY_SUCCEEDED/CANCELLED）可重试。
     * 保持 taskId/itemId，递增 attempt，重置失败 item 为 QUEUED。
     * 当前 attempt 第一个终态结果胜出，迟到结果记录后忽略。
     */
    @Transactional
    public ManagementTaskResponse retryTask(Long taskId) {
        TaskSnapshot task = persistencePort.findTask(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }

        if (!task.status().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 处于 " + task.status() + "，仅终态可重试");
        }

        // RECOVERY/SCAN 走各自专用重试入口（恢复页/扫描页会自行重置任务并重发执行事件）；
        // 若在此重置 QUEUED 而不重新入队，Worker 永不执行导致任务永久卡死。
        if (task.taskType() == TaskType.RECOVERY) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "恢复任务请使用专用重试入口: /api/manage/tasks/recovery/{id}/retry");
        }
        if (task.taskType() == TaskType.DIRECTORY_SCAN) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "目录扫描任务请使用专用重试入口: /api/manage/tasks/directory-scan/{id}/retry");
        }

        // 元数据刷新重试：章节项先归并到漫画，同一本漫画只执行一次 CAS。
        List<ItemSnapshot> items = persistencePort.findItemsByTaskId(taskId);
        taskLifecyclePolicy.prepareRetry(task.taskType(), items.stream()
                .map(item -> new TaskLifecyclePolicy.TaskItemReference(item.targetType(), item.targetId(),
                        item.operationType(), item.status()))
                .toList());

        int newAttempt = task.attempt() + 1;
        resetTaskAndItems(taskId, newAttempt, items);

        // 重新入队：按 item 类型发布对应事件，Worker 按新 attempt 重新执行
        for (ItemSnapshot item : items) {
            if (item.status() == ManagementTaskStatus.FAILED
                    || item.status() == ManagementTaskStatus.CANCELLED) {
                taskRetryPublisher.publish(taskId, new TaskRetryPublisher.RetryItem(
                        item.id(), item.operationType(), item.resultRefType(), item.resultRefId(),
                        item.targetType(), item.targetId()), newAttempt);
            }
        }

        log.info("重试任务 id={}, newAttempt={}", taskId, newAttempt);
        return taskResponseAssembler.toResponse(persistencePort.findTask(taskId));
    }

    /**
     * 重置管理任务与失败/取消 item 为 QUEUED（attempt 递增、清空进度与错误）。
     * <p>
     * 不包含任何重新入队——重新入队由各任务类型流程负责（retryTask 内按类型 republish，
     * RECOVERY/SCAN 由各自专用重试入口在事务提交后重发执行事件）。
     */
    private void resetTaskAndItems(Long taskId, int newAttempt, List<ItemSnapshot> items) {
        // 主任务重置由 Mapper 一次性写入全部状态字段。
        persistencePort.resetTask(taskId, newAttempt, LocalDateTime.now());

        // 失败/取消的 item 重置由 Mapper 统一处理 nullable 字段。
        for (ItemSnapshot item : items) {
            if (item.status() == ManagementTaskStatus.FAILED
                    || item.status() == ManagementTaskStatus.CANCELLED) {
                String lockKey = TaskLockKey.of(item.targetType(), item.targetId(), item.operationType());
                persistencePort.resetItem(item.id(), newAttempt, lockKey, LocalDateTime.now());
            }
        }
    }

    /**
     * 供 RECOVERY/SCAN 独立重试流程同步统一任务状态：仅重置为 QUEUED，不重新入队，
     * 执行事件由调用方在其事务提交后重发。
     */
    @Transactional
    public void resetTaskState(Long taskId) {
        TaskSnapshot task = persistencePort.findTask(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }
        if (!task.status().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 处于 " + task.status() + "，仅终态可重置");
        }
        List<ItemSnapshot> items = persistencePort.findItemsByTaskId(taskId);
        resetTaskAndItems(taskId, task.attempt() + 1, items);
    }


    // ======================== Item 状态更新 ========================

    /**
     * 更新任务阶段（供导入阶段事件处理器调用）。
     * <p>
     * 仅当任务非终态时生效：QUEUED → RUNNING，写入 {@code stage} 并更新进度。
     * 终态（含 CANCELLED）后的迟到阶段事件直接忽略，保证 CANCELLED 不回退。
     *
     * @param managementTaskId 统一任务 ID
     * @param stage            阶段
     * @param progress         进度（0-100，可空）
     * @return true 表示已更新
     */
    @Transactional
    public boolean updateStage(Long managementTaskId, TaskStage stage, Integer progress) {
        TaskSnapshot task = persistencePort.findTask(managementTaskId);
        if (task == null) {
            return false;
        }
        if (task.status().isTerminal()) {
            log.info("任务已终态 {}，忽略阶段更新: taskId={}, stage={}",
                    task.status(), managementTaskId, stage);
            return false;
        }

        LocalDateTime updateTime = LocalDateTime.now();
        boolean startTask = task.status() == ManagementTaskStatus.QUEUED;
        persistencePort.updateStage(managementTaskId, stage == null ? null : stage.name(), progress,
                startTask, startTask && task.startedAt() == null ? updateTime : null, updateTime);
        return true;
    }

    /**
     * 更新单个 item 的状态（供 MQ 事件处理器调用）。
     * <p>
     * 规则：当前 attempt 第一个终态结果胜出，迟到结果记录后忽略。
     */
    @Transactional
    public ManagementTaskItemResponse updateItemStatus(Long itemId,
                                                        ManagementTaskStatus newStatus,
                                                        String errorMessage,
                                                        String resultRefType,
                                                        Long resultRefId) {
        return updateItemStatus(itemId, newStatus, errorMessage, resultRefType, resultRefId, 0);
    }

    /**
     * 更新单个 item 的状态（供新管理命令结果事件处理器调用）。
     * <p>
     * 规则：当前 attempt 第一个终态结果胜出，迟到结果记录后忽略；
     * 结果事件携带 attempt，旧 attempt 结果直接忽略（不覆盖新 attempt）。
     */
    @Transactional
    public ManagementTaskItemResponse updateItemStatus(Long itemId,
                                                        ManagementTaskStatus newStatus,
                                                        String errorMessage,
                                                        String resultRefType,
                                                        Long resultRefId,
                                                        int attempt) {
        ItemSnapshot item = persistencePort.findItem(itemId);
        if (item == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务项不存在: " + itemId);
        }

        // 旧 attempt 结果：不覆盖当前 attempt 状态
        if (attempt > 0 && item.attempt() != null && !item.attempt().equals(attempt)) {
            log.info("item {} attempt={} 与结果事件 attempt={} 不匹配，忽略旧 attempt 结果 {}",
                    itemId, item.attempt(), attempt, newStatus);
            return taskResponseAssembler.toItemResponse(item);
        }

        // 如果已经处于终态（同一 attempt），忽略迟到结果
        if (item.status().isTerminal()) {
            log.info("item {} 已处于终态 {}（attempt={}），忽略迟到状态更新 {}",
                    itemId, item.status(), item.attempt(), newStatus);
            return taskResponseAssembler.toItemResponse(item);
        }

        Integer expectedAttempt = item.attempt();
        boolean isFirstRunning = newStatus == ManagementTaskStatus.RUNNING && item.startedAt() == null;
        LocalDateTime updateTime = LocalDateTime.now();
        LocalDateTime startedTime = isFirstRunning ? updateTime : item.startedAt();

        int affectedRows = persistencePort.updateItemStatus(itemId, expectedAttempt, newStatus.name(),
                errorMessage, resultRefType, resultRefId, isFirstRunning ? startedTime : null,
                newStatus.isTerminal() ? updateTime : null, updateTime);
        if (affectedRows == 0) {
            log.info("任务项状态更新因并发状态或 attempt 变化被忽略: itemId={}, attempt={}, status={}",
                    itemId, attempt, newStatus);
            return taskResponseAssembler.toItemResponse(persistencePort.findItem(itemId));
        }

        // 重新聚合主任务状态
        taskAggregationService.aggregate(item.taskId());

        return taskResponseAssembler.toItemResponse(persistencePort.findItem(itemId));
    }

    /**
     * 更新 item 进度（供 Worker 进度事件处理器调用）。
     * <p>
     * 仅当前 attempt 且非终态时生效；旧 attempt 进度事件忽略。
     *
     * @return true 表示进度已更新
     */
    @Transactional
    public boolean updateItemProgress(Long itemId, int attempt, int progress, String stage) {
        ItemSnapshot item = persistencePort.findItem(itemId);
        if (item == null) {
            return false;
        }
        if (attempt > 0 && item.attempt() != null && !item.attempt().equals(attempt)) {
            log.info("item {} attempt={} 与进度事件 attempt={} 不匹配，忽略旧进度",
                    itemId, item.attempt(), attempt);
            return false;
        }
        if (item.status().isTerminal()) {
            return false;
        }

        LocalDateTime updateTime = LocalDateTime.now();
        boolean isStarted = item.status() == ManagementTaskStatus.QUEUED;
        int affectedRows = persistencePort.updateItemProgress(itemId, item.attempt(), progress, isStarted,
                isStarted && item.startedAt() == null ? updateTime : null, updateTime);
        if (affectedRows == 0) {
            return false;
        }

        if (isStarted) {
            taskAggregationService.aggregate(item.taskId());
        }
        return true;
    }

    // ======================== 查询辅助 ========================

    /**
     * 根据幂等键查询任务，未命中返回 null。
     * <p>
     * 内部方法，返回数据库实体 {@link ManagementTask}，禁止用于接口响应；对外使用 {@code dto/} 包对应 DTO/VO。
     */
    public TaskInternalQueryService.TaskSnapshot findByIdempotencyKey(String idempotencyKey) {
        return taskInternalQueryService.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * 查询目标当前活跃（QUEUED/RUNNING/CANCELLING）的任务项。
     * <p>
     * 内部方法，返回数据库实体 {@link ManagementTaskItem}，禁止用于接口响应；对外使用 {@code dto/} 包对应 DTO/VO。
     */
    public TaskInternalQueryService.ItemSnapshot findActiveItem(String targetType, Long targetId, TaskType operationType) {
        return taskInternalQueryService.findActiveItem(targetType, targetId, operationType);
    }

    /**
     * 统计任务下尚未结束（QUEUED/RUNNING/CANCELLING）的目标项数量。
     * 供结果事件处理器判断任务是否已全部完成（最后一项完成时触发整本聚合）。
     */
    public long countActiveItems(Long taskId) {
        return taskInternalQueryService.countActiveItems(taskId);
    }

    /** 统计指定漫画在元数据刷新任务中尚未终态的漫画/章节项。 */
    public long countActiveMetadataItems(Long taskId, Long comicId) {
        return persistencePort.countActiveMetadataItems(taskId, comicId);
    }

    // ======================== 聚合 ========================

    /**
     * 重新聚合主任务状态（供元数据刷新完成/失败流程在自定义短事务内复用现有聚合逻辑）。
     * <p>
     * 委托 {@link TaskAggregationService}；必须在事务内调用（自身无事务边界，
     * 由调用方的事务承载），全部 item 到终态时任务随之流转到 SUCCEEDED/FAILED 等。
     */
    public void reaggregateTask(Long taskId) {
        taskAggregationService.aggregate(taskId);
    }

    // ======================== 辅助方法 ========================

}
