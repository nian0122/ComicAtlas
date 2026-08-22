package com.comicatlas.api.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.export.entity.ExportTask;
import com.comicatlas.api.export.mapper.ExportTaskMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ExportTaskStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskStage;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一管理任务服务。
 * <p>
 * 提供任务创建（幂等/目标锁）、分页查询、详情、cancel、retry、item 状态更新。
 * 不塞具体业务 payload，业务扩展通过 management_task_id 一对一引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagementTaskService {

    /** 目标类型：漫画。 */
    private static final String TARGET_TYPE_COMIC = "COMIC";
    /** 目标类型：章节。 */
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";
    /** 目标类型：媒体。 */
    private static final String TARGET_TYPE_MEDIA = "MEDIA";
    /** 结果引用类型：回收清单（RESTORE/PURGE 定位 manifest 目录）。 */
    private static final String RESULT_REF_TYPE_TRASH_MANIFEST = "TRASH_MANIFEST";
    /** 初始 attempt 次数。 */
    private static final int INITIAL_ATTEMPT = 1;

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final OutboxService outboxService;
    private final ImportTaskMapper importTaskMapper;
    private final ImportRetryCoordinator importRetryCoordinator;

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
                String expectedHash = sha256(payload);
                if (expectedHash.equals(existing.getIdempotencyPayloadHash())) {
                    log.info("幂等命中 idempotencyKey={}, 返回已有任务 {}", idempotencyKey, existing.getId());
                    return toResponse(existing);
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
            task.setIdempotencyPayloadHash(sha256(payload));
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

        // 创建目标项（带目标冲突锁检查 + 元数据刷新漫画 CAS）
        List<ManagementTaskItem> items = new ArrayList<>();
        if (request.getTargets() != null) {
            for (CreateManagementTaskRequest.TaskTarget target : request.getTargets()) {
                TaskType opType = target.getOperationType() != null
                        ? target.getOperationType()
                        : request.getTaskType();

                // 元数据刷新：同一事务 CAS 漫画 READY→REFRESHING（0 行 = 非 READY 或并发被占用 → 409）
                if (opType == TaskType.METADATA_REFRESH) {
                    int casRows = comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                            .eq(Comic::getId, target.getTargetId())
                            .eq(Comic::getStatus, ComicStatus.READY)
                            .set(Comic::getStatus, ComicStatus.REFRESHING));
                    if (casRows == 0) {
                        throw new ConflictException(
                                String.format("漫画 %d 不是 READY 或已被其他任务占用，无法创建元数据刷新任务",
                                        target.getTargetId()));
                    }
                }

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
        return toResponse(task);
    }

    // ======================== 查询 ========================

    /**
     * 分页查询任务列表，支持 type/status/batch/target 过滤。
     */
    public IPage<ManagementTaskResponse> listTasks(int page, int size,
                                                    TaskType type,
                                                    ManagementTaskStatus status,
                                                    String batchId,
                                                    String targetType,
                                                    Long targetId) {
        LambdaQueryWrapper<ManagementTask> wrapper = new LambdaQueryWrapper<>();

        if (type != null) {
            wrapper.eq(ManagementTask::getTaskType, type);
        }
        if (status != null) {
            wrapper.eq(ManagementTask::getStatus, status);
        }
        if (batchId != null && !batchId.isBlank()) {
            wrapper.eq(ManagementTask::getBatchId, batchId);
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(ManagementTask::getTargetType, targetType);
        }

        // targetId 过滤：经 item 归属解析找到 task_id 列表（章节/媒体级 item 归入父漫画）
        if (targetId != null) {
            List<Long> taskIds = itemMapper.selectTaskIdsByTarget(targetId);
            if (taskIds.isEmpty()) {
                // 没有匹配的目标，返回空页
                IPage<ManagementTaskResponse> emptyPage = new Page<>(page, size);
                emptyPage.setTotal(0);
                emptyPage.setRecords(List.of());
                return emptyPage;
            }
            wrapper.in(ManagementTask::getId, taskIds);
        }

        wrapper.orderByDesc(ManagementTask::getCreatedAt);

        IPage<ManagementTask> taskPage = taskMapper.selectPage(new Page<>(page, size), wrapper);

        IPage<ManagementTaskResponse> responsePage = new Page<>(page, size);
        responsePage.setTotal(taskPage.getTotal());
        List<ManagementTaskResponse> responses = taskPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        enrichTargetSummaries(taskPage.getRecords(), responses);
        responsePage.setRecords(responses);
        return responsePage;
    }

    /**
     * 获取任务详情。
     */
    public ManagementTaskResponse getTask(Long taskId) {
        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }
        ManagementTaskResponse response = toResponse(task);
        enrichTargetSummaries(List.of(task), List.of(response));
        return response;
    }

    /**
     * 获取任务的逐目标项列表。
     */
    public List<ManagementTaskItemResponse> getTaskItems(Long taskId) {
        // 验证任务存在
        if (taskMapper.selectById(taskId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在: " + taskId);
        }
        List<ManagementTaskItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId)
                        .orderByAsc(ManagementTaskItem::getId));
        return items.stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());
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
            return toResponse(task);
        }

        if (task.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "任务 " + taskId + " 已处于终态 " + task.getStatus() + "，无法取消");
        }

        // 如果已经在取消中，不重复操作
        if (task.getStatus() == ManagementTaskStatus.CANCELLING) {
            return toResponse(task);
        }

        task.setStatus(ManagementTaskStatus.CANCELLING);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 将未开始的 item 标记为 CANCELLED
        itemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTaskId, taskId)
                .in(ManagementTaskItem::getStatus, ManagementTaskStatus.QUEUED)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED)
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));

        // 重新聚合状态
        aggregateTaskStatus(taskId);

        ManagementTask updated = taskMapper.selectById(taskId);
        return toResponse(updated);
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

        // 元数据刷新重试：先在同一事务 CAS 漫画 READY→REFRESHING（comic 非 READY → 冲突 409）
        List<ManagementTaskItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId));
        if (task.getTaskType() == TaskType.METADATA_REFRESH) {
            for (ManagementTaskItem item : items) {
                if (item.getOperationType() != TaskType.METADATA_REFRESH
                        || !TARGET_TYPE_COMIC.equals(item.getTargetType())) {
                    continue;
                }
                int casRows = comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                        .eq(Comic::getId, item.getTargetId())
                        .eq(Comic::getStatus, ComicStatus.READY)
                        .set(Comic::getStatus, ComicStatus.REFRESHING));
                if (casRows == 0) {
                    throw new ConflictException(
                            String.format("漫画 %d 不是 READY 或已被其他任务占用，无法重试元数据刷新",
                                    item.getTargetId()));
                }
            }
        }

        int newAttempt = task.getAttempt() + 1;
        resetTaskAndItems(taskId, newAttempt, items);

        // 重新入队：按 item 类型发布对应事件，Worker 按新 attempt 重新执行
        for (ManagementTaskItem item : items) {
            if (item.getStatus() == ManagementTaskStatus.FAILED
                    || item.getStatus() == ManagementTaskStatus.CANCELLED) {
                republishCommand(taskId, item, newAttempt);
                // EXPORT 走独立导出链路（export.task.queue），单独重新入队
                republishExportCommand(taskId, item, newAttempt);
                // IMPORT 走独立导入链路（import.task.queue），重新发布导入事件
                republishImportCommand(taskId, item, newAttempt);
            }
        }

        log.info("重试任务 id={}, newAttempt={}", taskId, newAttempt);
        return toResponse(taskMapper.selectById(taskId));
    }

    /**
     * 重置管理任务与失败/取消 item 为 QUEUED（attempt 递增、清空进度与错误）。
     * <p>
     * 不包含任何重新入队——重新入队由各任务类型流程负责（retryTask 内按类型 republish，
     * RECOVERY/SCAN 由各自专用重试入口在事务提交后重发执行事件）。
     */
    private void resetTaskAndItems(Long taskId, int newAttempt, List<ManagementTaskItem> items) {
        // 重置主任务（使用 LambdaUpdateWrapper 确保 null 字段写入）
        taskMapper.update(null, new LambdaUpdateWrapper<ManagementTask>()
                .eq(ManagementTask::getId, taskId)
                .set(ManagementTask::getStatus, ManagementTaskStatus.QUEUED)
                .set(ManagementTask::getAttempt, newAttempt)
                .set(ManagementTask::getProgress, 0)
                .set(ManagementTask::getSuccessCount, 0)
                .set(ManagementTask::getFailureCount, 0)
                .set(ManagementTask::getCancelledCount, 0)
                .set(ManagementTask::getErrorMessage, null)
                .set(ManagementTask::getErrorDetail, null)
                .set(ManagementTask::getStage, null)
                .set(ManagementTask::getStartedAt, null)
                .set(ManagementTask::getCompletedAt, null)
                .set(ManagementTask::getUpdatedAt, LocalDateTime.now()));

        // 重置失败/取消的 item（使用 LambdaUpdateWrapper 确保 nullable 字段写入）
        for (ManagementTaskItem item : items) {
            if (item.getStatus() == ManagementTaskStatus.FAILED
                    || item.getStatus() == ManagementTaskStatus.CANCELLED) {
                String lockKey = ManagementTaskItem.buildLockKey(
                        item.getTargetType(), item.getTargetId(), item.getOperationType());
                itemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getId, item.getId())
                        .set(ManagementTaskItem::getStatus, ManagementTaskStatus.QUEUED)
                        .set(ManagementTaskItem::getAttempt, newAttempt)
                        .set(ManagementTaskItem::getProgress, 0)
                        .set(ManagementTaskItem::getErrorMessage, null)
                        .set(ManagementTaskItem::getStartedAt, null)
                        .set(ManagementTaskItem::getCompletedAt, null)
                        .set(ManagementTaskItem::getLockKey, lockKey)
                        .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
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

    /**
     * 为被重置的 item 重新发布 ManagementCommandRequestedEvent 到 Outbox。
     * <p>
     * 仅适用于统一命令管线操作（LQ/HQ/转码/刷新/整本删除/回收/恢复/清理）；
     * IMPORT/RECOVERY/EXPORT/SCAN 等旧任务重试由各自流程重新入队。
     * <p>
     * RESTORE/PURGE 类操作通过 result_ref（TRASH_MANIFEST → 回收任务 taskId）定位清单目录。
     */
    private void republishCommand(Long taskId, ManagementTaskItem item, int newAttempt) {
        TaskType operation = item.getOperationType();
        if (operation == null || !COMMAND_OPS.contains(operation)) {
            return;
        }
        Long manifestTaskId = RESULT_REF_TYPE_TRASH_MANIFEST.equals(item.getResultRefType())
                ? item.getResultRefId() : null;
        ManagementCommandRequestedEvent event = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), INITIAL_ATTEMPT,
                taskId, item.getId(), newAttempt,
                operation.name(), item.getTargetType(), item.getTargetId(), manifestTaskId);
        outboxService.enqueue(event, MqExchanges.MANAGEMENT, MqRoutingKeys.COMMAND_REQUESTED,
                taskId, item.getId(), newAttempt);
        log.info("重试已重新发布命令: taskId={}, itemId={}, attempt={}, op={}, target={}:{}, manifestTaskId={}",
                taskId, item.getId(), newAttempt, operation.name(), item.getTargetType(), item.getTargetId(), manifestTaskId);
    }

    /**
     * EXPORT 任务重试：重置导出专表为 PENDING 并重新发布 ExportTaskCreatedEvent。
     * <p>
     * 导出走独立链路（export.task.queue），不经 ManagementCommandDispatcher，
     * 重试时必须主动重新入队，否则 item 重置为 QUEUED 后 Worker 永远不会收到命令而卡死。
     */
    private void republishExportCommand(Long taskId, ManagementTaskItem item, int newAttempt) {
        if (item.getOperationType() != TaskType.EXPORT) {
            return;
        }
        ExportTask exportTask = exportTaskMapper.selectOne(new LambdaQueryWrapper<ExportTask>()
                .eq(ExportTask::getManagementTaskId, taskId));
        if (exportTask == null) {
            log.warn("导出专表不存在，跳过导出重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        exportTaskMapper.update(null, new LambdaUpdateWrapper<ExportTask>()
                .eq(ExportTask::getId, exportTask.getId())
                .set(ExportTask::getStatus, ExportTaskStatus.PENDING)
                .set(ExportTask::getProgress, 0)
                .set(ExportTask::getErrorMsg, null)
                .set(ExportTask::getCompletedAt, null));

        ExportTaskCreatedEvent event = new ExportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(),
                exportTask.getId(), exportTask.getComicId(),
                exportTask.getFormat() == null ? ExportFormats.ZIP : exportTask.getFormat());
        outboxService.enqueue(event, MqExchanges.EXPORT, MqRoutingKeys.TASK_CREATED,
                taskId, item.getId(), newAttempt);
        log.info("导出任务重试已重新入队: taskId={}, itemId={}, attempt={}, exportTaskId={}, comicId={}",
                taskId, item.getId(), newAttempt, exportTask.getId(), exportTask.getComicId());
    }

    /**
     * IMPORT 任务重试：委托 ImportRetryCoordinator 重新入队。
     * <p>
     * 导入走独立链路（import.task.queue），不经 ManagementCommandDispatcher；
     * 重试时必须主动重新发布导入事件，否则 item 重置为 QUEUED 后 Worker 永远不会收到命令而卡死。
     * coordinator 内置终态守卫：仅当 import_task 仍处于终态（FAILED/CANCELLED）才执行重试入队，
     * 与导入任务页重试（ImportServiceImpl.retryTask 已先重置 import_task）并存时不会重复入队。
     */
    private void republishImportCommand(Long taskId, ManagementTaskItem item, int newAttempt) {
        if (item.getOperationType() != TaskType.IMPORT) {
            return;
        }
        ImportTask importTask = importTaskMapper.selectOne(
                new LambdaQueryWrapper<ImportTask>()
                        .eq(ImportTask::getManagementTaskId, taskId));
        if (importTask == null) {
            log.warn("导入任务不存在，跳过导入重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        boolean retried = importRetryCoordinator.retry(importTask);
        // 幂等防重：导入页重试已先重置 import_task 为 PENDING（ImportServiceImpl.retryTask 链路），
        // 此处返回 false 且为 PENDING 属预期，不重复入队；其余非终态说明导入任务状态与管理任务
        // 不一致，若静默跳过会导致管理任务重置 QUEUED 但导入永不入队，故抛冲突让本事务回滚。
        if (!retried && importTask.getStatus() != ImportTaskStatus.PENDING) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "导入任务非终态且未被重置，无法重试入队: taskId=" + taskId
                            + ", importTaskId=" + importTask.getId()
                            + ", status=" + importTask.getStatus());
        }
        log.info("导入任务重试已重新入队: taskId={}, itemId={}, attempt={}, importTaskId={}, retried={}",
                taskId, item.getId(), newAttempt, importTask.getId(), retried);
    }

    /** 统一命令管线操作类型集合。 */
    private static final Set<TaskType> COMMAND_OPS = Set.of(
            TaskType.LQ_GENERATE, TaskType.LQ_REGENERATE, TaskType.HQ_DELETE,
            TaskType.TRANSCODE, TaskType.METADATA_REFRESH, TaskType.COMIC_DELETE,
            TaskType.MEDIA_UPLOAD, TaskType.MEDIA_REPLACE, TaskType.MEDIA_TRASH,
            TaskType.CHAPTER_TRASH, TaskType.COMIC_RESTORE, TaskType.CHAPTER_RESTORE,
            TaskType.MEDIA_RESTORE, TaskType.COMIC_PURGE, TaskType.CHAPTER_PURGE,
            TaskType.MEDIA_PURGE);

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

        LambdaUpdateWrapper<ManagementTask> taskUpdate = new LambdaUpdateWrapper<ManagementTask>()
                .eq(ManagementTask::getId, managementTaskId)
                .set(ManagementTask::getStage, stage != null ? stage.name() : null)
                .set(ManagementTask::getUpdatedAt, LocalDateTime.now());
        if (progress != null && progress >= 0) {
            taskUpdate.set(ManagementTask::getProgress, progress);
        }
        if (task.getStatus() == ManagementTaskStatus.QUEUED) {
            taskUpdate.set(ManagementTask::getStatus, ManagementTaskStatus.RUNNING);
            if (task.getStartedAt() == null) {
                taskUpdate.set(ManagementTask::getStartedAt, LocalDateTime.now());
            }
        }
        taskMapper.update(null, taskUpdate);
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
            return toItemResponse(item);
        }

        // 如果已经处于终态（同一 attempt），忽略迟到结果
        if (item.getStatus().isTerminal()) {
            log.info("item {} 已处于终态 {}（attempt={}），忽略迟到状态更新 {}",
                    itemId, item.getStatus(), item.getAttempt(), newStatus);
            return toItemResponse(item);
        }

        item.setStatus(newStatus);
        item.setUpdatedAt(LocalDateTime.now());

        if (newStatus == ManagementTaskStatus.RUNNING && item.getStartedAt() == null) {
            item.setStartedAt(LocalDateTime.now());
        }

        LambdaUpdateWrapper<ManagementTaskItem> updateWrapper = new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, itemId)
                .set(ManagementTaskItem::getStatus, newStatus)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now());

        if (newStatus == ManagementTaskStatus.RUNNING && item.getStartedAt() == null) {
            updateWrapper.set(ManagementTaskItem::getStartedAt, LocalDateTime.now());
        }

        if (newStatus.isTerminal()) {
            updateWrapper.set(ManagementTaskItem::getCompletedAt, LocalDateTime.now());
            updateWrapper.set(ManagementTaskItem::getLockKey, null);
        }

        if (errorMessage != null) {
            updateWrapper.set(ManagementTaskItem::getErrorMessage, errorMessage);
        }
        if (resultRefType != null) {
            updateWrapper.set(ManagementTaskItem::getResultRefType, resultRefType);
        }
        if (resultRefId != null) {
            updateWrapper.set(ManagementTaskItem::getResultRefId, resultRefId);
        }

        itemMapper.update(null, updateWrapper);

        // 重新聚合主任务状态
        aggregateTaskStatus(item.getTaskId());

        return toItemResponse(itemMapper.selectById(itemId));
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

        LambdaUpdateWrapper<ManagementTaskItem> updateWrapper = new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, itemId)
                .set(ManagementTaskItem::getProgress, progress)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now());
        boolean isStarted = item.getStatus() == ManagementTaskStatus.QUEUED;
        if (isStarted) {
            updateWrapper.set(ManagementTaskItem::getStatus, ManagementTaskStatus.RUNNING);
            if (item.getStartedAt() == null) {
                updateWrapper.set(ManagementTaskItem::getStartedAt, LocalDateTime.now());
            }
        }
        itemMapper.update(null, updateWrapper);

        if (isStarted) {
            aggregateTaskStatus(item.getTaskId());
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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return taskMapper.selectOne(
                new LambdaQueryWrapper<ManagementTask>()
                        .eq(ManagementTask::getIdempotencyKey, idempotencyKey));
    }

    /**
     * 查询目标当前活跃（QUEUED/RUNNING/CANCELLING）的任务项。
     * <p>
     * 内部方法，返回数据库实体 {@link ManagementTaskItem}，禁止用于接口响应；对外使用 {@code dto/} 包对应 DTO/VO。
     */
    public ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType) {
        return itemMapper.selectOne(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTargetType, targetType)
                        .eq(ManagementTaskItem::getTargetId, targetId)
                        .eq(ManagementTaskItem::getOperationType, operationType)
                        .in(ManagementTaskItem::getStatus,
                                ManagementTaskStatus.QUEUED,
                                ManagementTaskStatus.RUNNING,
                                ManagementTaskStatus.CANCELLING)
                        .orderByDesc(ManagementTaskItem::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 统计任务下尚未结束（QUEUED/RUNNING/CANCELLING）的目标项数量。
     * 供结果事件处理器判断任务是否已全部完成（最后一项完成时触发整本聚合）。
     */
    public long countActiveItems(Long taskId) {
        return itemMapper.selectCount(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId)
                        .in(ManagementTaskItem::getStatus,
                                ManagementTaskStatus.QUEUED,
                                ManagementTaskStatus.RUNNING,
                                ManagementTaskStatus.CANCELLING));
    }

    // ======================== 聚合 ========================

    /**
     * 重新聚合主任务状态（供元数据刷新完成/失败流程在自定义短事务内复用现有聚合逻辑）。
     * <p>
     * 内部调用 {@link #aggregateTaskStatus}；必须在事务内调用（自身无事务边界，
     * 由调用方的事务承载），全部 item 到终态时任务随之流转到 SUCCEEDED/FAILED 等。
     */
    public void reaggregateTask(Long taskId) {
        aggregateTaskStatus(taskId);
    }

    /**
     * 根据所有 item 状态聚合主任务状态和计数。
     */
    private void aggregateTaskStatus(Long taskId) {
        List<ManagementTaskItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .eq(ManagementTaskItem::getTaskId, taskId));

        ManagementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        long successCount = items.stream()
                .filter(item -> item.getStatus() == ManagementTaskStatus.SUCCEEDED).count();
        long failureCount = items.stream()
                .filter(item -> item.getStatus() == ManagementTaskStatus.FAILED).count();
        long cancelledCount = items.stream()
                .filter(item -> item.getStatus() == ManagementTaskStatus.CANCELLED).count();

        task.setSuccessCount((int) successCount);
        task.setFailureCount((int) failureCount);
        task.setCancelledCount((int) cancelledCount);

        // 聚合进度
        long total = items.size();
        if (total > 0) {
            long completed = successCount + failureCount + cancelledCount;
            task.setProgress((int) (completed * 100 / total));
        }

        // 聚合状态
        boolean hasRunning = items.stream()
                .anyMatch(item -> item.getStatus() == ManagementTaskStatus.RUNNING
                        || item.getStatus() == ManagementTaskStatus.CANCELLING);
        boolean hasQueued = items.stream()
                .anyMatch(item -> item.getStatus() == ManagementTaskStatus.QUEUED);

        // 如果主任务正在取消中，且没有 running/queued 项了，标记为 CANCELLED
        if (task.getStatus() == ManagementTaskStatus.CANCELLING && !hasRunning && !hasQueued) {
            task.setStatus(ManagementTaskStatus.CANCELLED);
            task.setCompletedAt(LocalDateTime.now());
        } else if (!hasRunning && !hasQueued) {
            // 所有项都到终态
            if (successCount == total) {
                task.setStatus(ManagementTaskStatus.SUCCEEDED);
                task.setCompletedAt(LocalDateTime.now());
            } else if (failureCount == total) {
                task.setStatus(ManagementTaskStatus.FAILED);
                task.setCompletedAt(LocalDateTime.now());
            } else if (cancelledCount == total) {
                task.setStatus(ManagementTaskStatus.CANCELLED);
                task.setCompletedAt(LocalDateTime.now());
            } else {
                task.setStatus(ManagementTaskStatus.PARTIALLY_SUCCEEDED);
                task.setCompletedAt(LocalDateTime.now());
            }
        } else if (hasRunning || task.getStatus() == ManagementTaskStatus.RUNNING) {
            // 确保是 RUNNING 状态
            if (task.getStatus() == ManagementTaskStatus.QUEUED) {
                task.setStatus(ManagementTaskStatus.RUNNING);
                task.setStartedAt(LocalDateTime.now());
            }
        }

        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 聚合失败原因到任务级：终态失败时汇总失败 item 的首条错误，供任务卡片/详情直接展示。
        // 单独用 UpdateWrapper 显式更新——updateById 忽略 null 字段，其余状态须显式清空避免残留旧错误。
        String aggregatedError = task.getStatus() == ManagementTaskStatus.FAILED
                || task.getStatus() == ManagementTaskStatus.PARTIALLY_SUCCEEDED
                ? items.stream()
                        .filter(item -> item.getStatus() == ManagementTaskStatus.FAILED)
                        .map(ManagementTaskItem::getErrorMessage)
                        .filter(message -> message != null && !message.isBlank())
                        .findFirst()
                        .orElse(null)
                : null;
        taskMapper.update(null, new LambdaUpdateWrapper<ManagementTask>()
                .eq(ManagementTask::getId, taskId)
                .set(ManagementTask::getErrorMessage, aggregatedError));
    }

    // ======================== 辅助方法 ========================

    private ManagementTaskResponse toResponse(ManagementTask task) {
        ManagementTaskResponse response = new ManagementTaskResponse();
        response.setId(task.getId());
        response.setTaskType(task.getTaskType());
        response.setOperation(task.getOperation());
        response.setTargetType(task.getTargetType());
        response.setBatchId(task.getBatchId());
        response.setBatch(task.getBatch());
        response.setStatus(task.getStatus());
        response.setStage(task.getStage());
        response.setProgress(task.getProgress());
        response.setTotalCount(task.getTotalCount());
        response.setSuccessCount(task.getSuccessCount());
        response.setFailureCount(task.getFailureCount());
        response.setCancelledCount(task.getCancelledCount());
        response.setErrorMessage(task.getErrorMessage());
        response.setAttempt(task.getAttempt());
        response.setVersion(task.getVersion());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setCompletedAt(task.getCompletedAt());
        return response;
    }

    private void enrichTargetSummaries(List<ManagementTask> tasks,
                                       List<ManagementTaskResponse> responses) {
        if (tasks.isEmpty()) {
            return;
        }
        Map<Long, ManagementTaskResponse> responseByTaskId = new HashMap<>();
        for (ManagementTaskResponse response : responses) {
            responseByTaskId.put(response.getId(), response);
        }
        List<ManagementTaskItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ManagementTaskItem>()
                        .in(ManagementTaskItem::getTaskId,
                                tasks.stream().map(ManagementTask::getId).toList())
                        .orderByAsc(ManagementTaskItem::getId));
        Map<Long, ManagementTaskItem> firstItemByTaskId = new HashMap<>();
        for (ManagementTaskItem item : items) {
            firstItemByTaskId.putIfAbsent(item.getTaskId(), item);
        }

        // 每任务首个目标项解析到父漫画（COMIC 即自身，CHAPTER/MEDIA 向上溯源）
        Map<Long, Long> parentComicIdByTaskId = resolveParentComicIds(firstItemByTaskId);

        Map<Long, Comic> comics = new HashMap<>();
        List<Long> comicIds = parentComicIdByTaskId.values().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!comicIds.isEmpty()) {
            for (Comic comic : comicMapper.selectBatchIds(comicIds)) {
                comics.put(comic.getId(), comic);
            }
        }
        for (ManagementTaskItem item : firstItemByTaskId.values()) {
            ManagementTaskResponse response = responseByTaskId.get(item.getTaskId());
            if (response == null) {
                continue;
            }
            Long parentComicId = parentComicIdByTaskId.get(item.getTaskId());
            Comic comic = parentComicId != null ? comics.get(parentComicId) : null;
            if (TARGET_TYPE_COMIC.equals(response.getTargetType()) && parentComicId != null) {
                // 漫画级任务的目标 ID 固定为父漫画 id，而非首个章节/媒体项的 id
                response.setTargetId(parentComicId);
            } else {
                response.setTargetId(item.getTargetId());
            }
            response.setTargetName(comic != null ? comic.getTitle() : null);
        }
    }

    /**
     * 批量解析任务首个目标项的父漫画 id。
     *
     * @param firstItemByTaskId 每任务插入序最早的目标项
     * @return taskId → 父漫画 id（无法解析时为 null）
     */
    private Map<Long, Long> resolveParentComicIds(Map<Long, ManagementTaskItem> firstItemByTaskId) {
        Map<Long, Long> parentComicIdByTaskId = new HashMap<>();
        Map<Long, Long> chapterIdToComicId = new HashMap<>();
        Map<Long, Long> mediaIdToChapterId = new HashMap<>();

        List<Long> chapterIds = firstItemByTaskId.values().stream()
                .filter(item -> TARGET_TYPE_CHAPTER.equals(item.getTargetType()))
                .map(ManagementTaskItem::getTargetId)
                .distinct()
                .toList();
        List<Long> mediaIds = firstItemByTaskId.values().stream()
                .filter(item -> TARGET_TYPE_MEDIA.equals(item.getTargetType()))
                .map(ManagementTaskItem::getTargetId)
                .distinct()
                .toList();

        if (!chapterIds.isEmpty()) {
            for (Chapter chapter : chapterMapper.selectBatchIds(chapterIds)) {
                chapterIdToComicId.put(chapter.getId(), chapter.getComicId());
            }
        }
        if (!mediaIds.isEmpty()) {
            for (Media media : mediaMapper.selectBatchIds(mediaIds)) {
                mediaIdToChapterId.put(media.getId(), media.getChapterId());
            }
        }
        if (!mediaIdToChapterId.isEmpty()) {
            List<Long> mediaChapterIds = mediaIdToChapterId.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!mediaChapterIds.isEmpty()) {
                for (Chapter chapter : chapterMapper.selectBatchIds(mediaChapterIds)) {
                    chapterIdToComicId.putIfAbsent(chapter.getId(), chapter.getComicId());
                }
            }
        }

        for (ManagementTaskItem item : firstItemByTaskId.values()) {
            Long parentComicId;
            if (TARGET_TYPE_COMIC.equals(item.getTargetType())) {
                parentComicId = item.getTargetId();
            } else if (TARGET_TYPE_CHAPTER.equals(item.getTargetType())) {
                parentComicId = chapterIdToComicId.get(item.getTargetId());
            } else if (TARGET_TYPE_MEDIA.equals(item.getTargetType())) {
                Long chapterId = mediaIdToChapterId.get(item.getTargetId());
                parentComicId = chapterId != null ? chapterIdToComicId.get(chapterId) : null;
            } else {
                parentComicId = null;
            }
            parentComicIdByTaskId.put(item.getTaskId(), parentComicId);
        }
        return parentComicIdByTaskId;
    }

    private ManagementTaskItemResponse toItemResponse(ManagementTaskItem item) {
        ManagementTaskItemResponse response = new ManagementTaskItemResponse();
        response.setId(item.getId());
        response.setTaskId(item.getTaskId());
        response.setTargetType(item.getTargetType());
        response.setTargetId(item.getTargetId());
        response.setOperationType(item.getOperationType());
        response.setStatus(item.getStatus());
        response.setAttempt(item.getAttempt());
        response.setProgress(item.getProgress());
        response.setResultRefType(item.getResultRefType());
        response.setResultRefId(item.getResultRefId());
        response.setErrorMessage(item.getErrorMessage());
        response.setVersion(item.getVersion());
        response.setCreatedAt(item.getCreatedAt());
        response.setUpdatedAt(item.getUpdatedAt());
        response.setStartedAt(item.getStartedAt());
        response.setCompletedAt(item.getCompletedAt());
        return response;
    }

    private static String sha256(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException("SHA-256 不可用", ex);
        }
    }
}
