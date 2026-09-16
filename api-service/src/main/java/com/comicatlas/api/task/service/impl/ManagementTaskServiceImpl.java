package com.comicatlas.api.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.task.service.TaskRetryPublisher;
import com.comicatlas.api.task.service.TaskResponseAssembler;
import com.comicatlas.api.task.service.TaskQueryService;
import com.comicatlas.api.task.service.TaskAggregationService;
import com.comicatlas.api.task.service.TaskInternalQueryService;
import com.comicatlas.api.task.service.MetadataRefreshTaskPolicy;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskStage;
import com.comicatlas.api.task.enums.TaskType;
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
import java.util.ArrayList;
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

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;
    private final TaskRetryPublisher taskRetryPublisher;
    private final TaskResponseAssembler taskResponseAssembler;
    private final TaskQueryService taskQueryService;
    private final TaskAggregationService taskAggregationService;
    private final TaskInternalQueryService taskInternalQueryService;
    private final MetadataRefreshTaskPolicy metadataRefreshTaskPolicy;

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
            ManagementTask existing = taskMapper.selectOne(
                    new LambdaQueryWrapper<ManagementTask>()
                            .eq(ManagementTask::getIdempotencyKey, idempotencyKey));
            if (existing != null) {
                String expectedHash = digestService.sha256(payload);
                if (expectedHash.equals(existing.getIdempotencyPayloadHash())) {
                    log.info("幂等命中 idempotencyKey={}, 返回已有任务 {}", idempotencyKey, existing.getId());
                    return taskResponseAssembler.toResponse(existing);
                }
                throw new ConflictException("幂等键 " + idempotencyKey + " 已存在但 payload 不匹配");
            }
        }

        // 构建主任务
        ManagementTask task = new ManagementTask();
        task.setTaskType(request.getTaskType());
        task.setOperation(request.getOperation());
        task.setTargetType(request.getTargetType());
        task.setBatchId(request.getBatchId());
        task.setBatch(request.getTargets() != null && request.getTargets().size() > 1);
        task.setStatus(ManagementTaskStatus.QUEUED);
        task.setProgress(0);
        task.setAttempt(INITIAL_ATTEMPT);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            task.setIdempotencyKey(idempotencyKey);
            task.setIdempotencyPayloadHash(digestService.sha256(payload));
        }

        int totalCount = 0;
        if (request.getTargets() != null) {
            totalCount = request.getTargets().size();
        }
        task.setTotalCount(totalCount);
        task.setSuccessCount(0);
        task.setFailureCount(0);
        task.setCancelledCount(0);

        taskMapper.insert(task);

        // 元数据刷新可能展开为多个章节项；同一本漫画只允许执行一次 READY→REFRESHING CAS。
        metadataRefreshTaskPolicy.lockOnCreate(request.getTaskType(), request.getTargets());

        // 创建目标项（带目标冲突锁检查）
        List<ManagementTaskItem> items = new ArrayList<>();
        if (request.getTargets() != null) {
            for (CreateManagementTaskRequest.TaskTarget target : request.getTargets()) {
                TaskType opType = target.getOperationType() != null
                        ? target.getOperationType()
                        : request.getTaskType();

                String lockKey = ManagementTaskItem.buildLockKey(
                        target.getTargetType(), target.getTargetId(), opType);

                // 检查目标冲突锁：查询是否有活跃项占用此 lock_key
                Long activeCount = itemMapper.selectCount(
                        new LambdaQueryWrapper<ManagementTaskItem>()
                                .eq(ManagementTaskItem::getLockKey, lockKey));
                if (activeCount > 0) {
                    throw new ConflictException(
                            String.format("目标 %s:%d 在操作 %s 中已有活跃任务项，无法创建新任务",
                                    target.getTargetType(), target.getTargetId(), opType));
                }

                ManagementTaskItem item = new ManagementTaskItem();
                item.setTaskId(task.getId());
                item.setTargetType(target.getTargetType());
                item.setTargetId(target.getTargetId());
                item.setOperationType(opType);
                item.setStatus(ManagementTaskStatus.QUEUED);
                item.setAttempt(INITIAL_ATTEMPT);
                item.setProgress(0);
                item.setLockKey(lockKey);
                items.add(item);
            }
        }

        if (!items.isEmpty()) {
            for (ManagementTaskItem item : items) {
                try {
                    itemMapper.insert(item);
                } catch (DuplicateKeyException ex) {
                    throw new ConflictException(
                            String.format("目标 %s:%d 在操作 %s 中已有活跃任务项",
                                    item.getTargetType(), item.getTargetId(), item.getOperationType()));
                }
            }
        }

        log.info("创建管理任务 id={}, type={}, items={}, idempotencyKey={}",
                task.getId(), request.getTaskType(), items.size(), idempotencyKey);
        return taskResponseAssembler.toResponse(task);
    }

    // ======================== 查询 ========================

    /** 分页查询任务列表。 */
    public IPage<ManagementTaskResponse> listTasks(int page, int size, TaskType type,
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
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }

        if (task.getStatus() == ManagementTaskStatus.CANCELLED) {
            return taskResponseAssembler.toResponse(task);
        }

        if (task.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 已处于终态 " + task.getStatus() + "，无法取消");
        }

        // 如果已经在取消中，不重复操作
        if (task.getStatus() == ManagementTaskStatus.CANCELLING) {
            return taskResponseAssembler.toResponse(task);
        }

        task.setStatus(ManagementTaskStatus.CANCELLING);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 将未开始的 item 标记为 CANCELLED
        LocalDateTime cancelTime = LocalDateTime.now();
        itemMapper.cancelQueuedByTask(taskId, cancelTime, cancelTime);

        // 重新聚合状态
        taskAggregationService.aggregate(taskId);
        List<ManagementTaskItem> cancelledItems = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>().eq(ManagementTaskItem::getTaskId, taskId));
        metadataRefreshTaskPolicy.releaseAfterCancel(task, taskId,
                taskInternalQueryService.countActiveItems(taskId), cancelledItems);

        ManagementTask updated = taskMapper.selectById(taskId);
        return taskResponseAssembler.toResponse(updated);
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
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }

        if (!task.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 处于 " + task.getStatus() + "，仅终态可重试");
        }

        // RECOVERY/SCAN 走各自专用重试入口（恢复页/扫描页会自行重置任务并重发执行事件）；
        // 若在此重置 QUEUED 而不重新入队，Worker 永不执行导致任务永久卡死。
        if (task.getTaskType() == TaskType.RECOVERY) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "恢复任务请使用专用重试入口: /api/manage/tasks/recovery/{id}/retry");
        }
        if (task.getTaskType() == TaskType.DIRECTORY_SCAN) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "目录扫描任务请使用专用重试入口: /api/manage/tasks/directory-scan/{id}/retry");
        }

        // 元数据刷新重试：章节项先归并到漫画，同一本漫画只执行一次 CAS。
        List<ManagementTaskItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId));
        metadataRefreshTaskPolicy.prepareRetry(task.getTaskType(), items);

        int newAttempt = task.getAttempt() + 1;
        resetTaskAndItems(taskId, newAttempt, items);

        // 重新入队：按 item 类型发布对应事件，Worker 按新 attempt 重新执行
        for (ManagementTaskItem item : items) {
            if (item.getStatus() == ManagementTaskStatus.FAILED
                    || item.getStatus() == ManagementTaskStatus.CANCELLED) {
                taskRetryPublisher.publish(taskId, item, newAttempt);
            }
        }

        log.info("重试任务 id={}, newAttempt={}", taskId, newAttempt);
        return taskResponseAssembler.toResponse(taskMapper.selectById(taskId));
    }

    /**
     * 重置管理任务与失败/取消 item 为 QUEUED（attempt 递增、清空进度与错误）。
     * <p>
     * 不包含任何重新入队——重新入队由各任务类型流程负责（retryTask 内按类型 republish，
     * RECOVERY/SCAN 由各自专用重试入口在事务提交后重发执行事件）。
     */
    private void resetTaskAndItems(Long taskId, int newAttempt, List<ManagementTaskItem> items) {
        // 主任务重置由 Mapper 一次性写入全部状态字段。
        taskMapper.resetForRetry(taskId, newAttempt, LocalDateTime.now());

        // 失败/取消的 item 重置由 Mapper 统一处理 nullable 字段。
        for (ManagementTaskItem item : items) {
            if (item.getStatus() == ManagementTaskStatus.FAILED
                    || item.getStatus() == ManagementTaskStatus.CANCELLED) {
                String lockKey = ManagementTaskItem.buildLockKey(
                        item.getTargetType(), item.getTargetId(), item.getOperationType());
                itemMapper.resetForRetry(item.getId(), newAttempt, lockKey, LocalDateTime.now());
            }
        }
    }

    /**
     * 供 RECOVERY/SCAN 独立重试流程同步统一任务状态：仅重置为 QUEUED，不重新入队，
     * 执行事件由调用方在其事务提交后重发。
     */
    @Transactional
    public void resetTaskState(Long taskId) {
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }
        if (!task.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 处于 " + task.getStatus() + "，仅终态可重置");
        }
        List<ManagementTaskItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId));
        resetTaskAndItems(taskId, task.getAttempt() + 1, items);
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
        ManagementTask task = taskMapper.selectById(managementTaskId);
        if (task == null) {
            return false;
        }
        if (task.isTerminal()) {
            log.info("任务已终态 {}，忽略阶段更新: taskId={}, stage={}",
                    task.getStatus(), managementTaskId, stage);
            return false;
        }

        LocalDateTime updateTime = LocalDateTime.now();
        boolean startTask = task.getStatus() == ManagementTaskStatus.QUEUED;
        taskMapper.updateStage(managementTaskId, stage == null ? null : stage.name(), progress,
                startTask, startTask && task.getStartedAt() == null ? updateTime : null, updateTime);
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
        ManagementTaskItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务项不存在: " + itemId);
        }

        // 旧 attempt 结果：不覆盖当前 attempt 状态
        if (attempt > 0 && item.getAttempt() != null && !item.getAttempt().equals(attempt)) {
            log.info("item {} attempt={} 与结果事件 attempt={} 不匹配，忽略旧 attempt 结果 {}",
                    itemId, item.getAttempt(), attempt, newStatus);
            return taskResponseAssembler.toItemResponse(item);
        }

        // 如果已经处于终态（同一 attempt），忽略迟到结果
        if (item.getStatus().isTerminal()) {
            log.info("item {} 已处于终态 {}（attempt={}），忽略迟到状态更新 {}",
                    itemId, item.getStatus(), item.getAttempt(), newStatus);
            return taskResponseAssembler.toItemResponse(item);
        }

        Integer expectedAttempt = item.getAttempt();
        boolean isFirstRunning = newStatus == ManagementTaskStatus.RUNNING && item.getStartedAt() == null;
        LocalDateTime updateTime = LocalDateTime.now();
        LocalDateTime startedTime = isFirstRunning ? updateTime : item.getStartedAt();

        int affectedRows = itemMapper.updateStatusIfActive(itemId, expectedAttempt, newStatus.name(),
                errorMessage, resultRefType, resultRefId, isFirstRunning ? startedTime : null,
                newStatus.isTerminal() ? updateTime : null, updateTime);
        if (affectedRows == 0) {
            log.info("任务项状态更新因并发状态或 attempt 变化被忽略: itemId={}, attempt={}, status={}",
                    itemId, attempt, newStatus);
            return taskResponseAssembler.toItemResponse(itemMapper.selectById(itemId));
        }

        // 重新聚合主任务状态
        taskAggregationService.aggregate(item.getTaskId());

        return taskResponseAssembler.toItemResponse(itemMapper.selectById(itemId));
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
        ManagementTaskItem item = itemMapper.selectById(itemId);
        if (item == null) {
            return false;
        }
        if (attempt > 0 && item.getAttempt() != null && !item.getAttempt().equals(attempt)) {
            log.info("item {} attempt={} 与进度事件 attempt={} 不匹配，忽略旧进度",
                    itemId, item.getAttempt(), attempt);
            return false;
        }
        if (item.getStatus().isTerminal()) {
            return false;
        }

        LocalDateTime updateTime = LocalDateTime.now();
        boolean isStarted = item.getStatus() == ManagementTaskStatus.QUEUED;
        int affectedRows = itemMapper.updateProgressIfActive(itemId, item.getAttempt(), progress, isStarted,
                isStarted && item.getStartedAt() == null ? updateTime : null, updateTime);
        if (affectedRows == 0) {
            return false;
        }

        if (isStarted) {
            taskAggregationService.aggregate(item.getTaskId());
        }
        return true;
    }

    // ======================== 查询辅助 ========================

    /**
     * 根据幂等键查询任务，未命中返回 null。
     * <p>
     * 内部方法，返回数据库实体 {@link ManagementTask}，禁止用于接口响应；对外使用 {@code dto/} 包对应 DTO/VO。
     */
    public ManagementTask findByIdempotencyKey(String idempotencyKey) {
        return taskInternalQueryService.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * 查询目标当前活跃（QUEUED/RUNNING/CANCELLING）的任务项。
     * <p>
     * 内部方法，返回数据库实体 {@link ManagementTaskItem}，禁止用于接口响应；对外使用 {@code dto/} 包对应 DTO/VO。
     */
    public ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType) {
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
        return itemMapper.countActiveMetadataItems(taskId, comicId);
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
