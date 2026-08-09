package com.comicatlas.api.management.batch.service;

import com.comicatlas.api.management.batch.BatchConflictException;
import com.comicatlas.api.management.batch.BatchReasonCode;
import com.comicatlas.api.management.batch.config.BatchProperties;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.comicatlas.api.management.batch.dto.BatchCreateResponse;
import com.comicatlas.api.management.batch.dto.BatchOperationRequest;
import com.comicatlas.api.management.batch.dto.BatchPreviewResponse;
import com.comicatlas.api.management.batch.dto.BlockedBatchItem;

/**
 * 批量操作服务 — 跨页选择快照 + 逐项物化。
 * <p>
 * 创建事务内：解析目标（IDS/FILTER 判别联合）→ 资格校验（allowed operation）
 * → 按稳定排序（id ASC）物化 management_task_item 快照，后续新增/删除不进入既有批次。
 * 危险操作（如 COMIC_PURGE）要求 server-issued preview token 二次确认。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOperationService {

    private static final String EXCHANGE = MqExchanges.MANAGEMENT;
    private static final String ROUTING_REQUEST = MqRoutingKeys.COMMAND_REQUESTED;

    private static final Set<TaskType> DANGEROUS_OPS = Set.of(TaskType.COMIC_PURGE);
    private static final Set<TaskType> SYNC_OPS = Set.of(TaskType.METADATA_UPDATE);
    private static final Set<TaskType> COMMAND_OPS = Set.of(
            TaskType.LQ_GENERATE, TaskType.LQ_REGENERATE, TaskType.HQ_DELETE,
            TaskType.TRANSCODE, TaskType.METADATA_REFRESH, TaskType.COMIC_DELETE,
            TaskType.COMIC_RESTORE, TaskType.COMIC_PURGE);

    private final BatchSelectionResolver selectionResolver;
    private final BatchEligibilityChecker eligibilityChecker;
    private final BatchPreviewTokenStore previewTokenStore;
    private final BatchMetadataExecutor metadataExecutor;
    private final BatchProperties batchProperties;
    private final ManagementTaskService managementTaskService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    // ======================== 预览 ========================

    /**
     * 批量选择预览：解析目标、校验资格，返回命中/可执行/被阻止数量。
     * 危险操作签发 preview token（绑定目标指纹 + 过期时间）。
     */
    public BatchPreviewResponse preview(BatchOperationRequest request) {
        int limit = batchProperties.getMaxItems() + 1;
        List<Long> resolved = selectionResolver.resolve(request.getSelection(), limit);
        if (resolved.isEmpty()) {
            return previewResponse(request, 0, 0, List.of(), null, null);
        }
        if (resolved.size() > batchProperties.getMaxItems()) {
            throw new BatchConflictException(BatchReasonCode.BATCH_SIZE_EXCEEDED,
                    "命中数量 " + resolved.size() + " 超过上限 " + batchProperties.getMaxItems());
        }

        BatchEligibilityChecker.Result eligibility = eligibilityChecker.evaluate(resolved, request.getOperation());
        boolean dangerous = DANGEROUS_OPS.contains(request.getOperation());
        String token = null;
        Instant expiresAt = null;
        if (dangerous && !eligibility.eligible().isEmpty()) {
            token = previewTokenStore.issue(request, eligibility.eligible(),
                    batchProperties.getPreviewTtlSeconds());
            expiresAt = Instant.ofEpochMilli(System.currentTimeMillis()
                    + batchProperties.getPreviewTtlSeconds() * 1000L);
        }
        return previewResponse(request, resolved.size(), eligibility.eligible().size(),
                eligibility.blocked(), token, expiresAt);
    }

    private BatchPreviewResponse previewResponse(BatchOperationRequest request, int selectedCount,
                                                 int eligibleCount, List<BlockedBatchItem> blocked,
                                                 String token, Instant expiresAt) {
        BatchPreviewResponse resp = new BatchPreviewResponse();
        resp.setOperation(request.getOperation());
        resp.setSelectedCount(selectedCount);
        resp.setEligibleCount(eligibleCount);
        resp.setBlocked(blocked);
        resp.setDangerous(DANGEROUS_OPS.contains(request.getOperation()));
        resp.setPreviewToken(token);
        resp.setExpiresAt(expiresAt);
        return resp;
    }

    // ======================== 创建批量任务 ========================

    /**
     * 创建批量任务：事务内解析目标 + 资格校验 + 物化 items 快照。
     * 支持 Idempotency-Key（同键同 payload 重放不重复）。
     */
    @Transactional
    public BatchCreateResponse createBatch(BatchOperationRequest request, String idempotencyKey) {
        String payload = toJson(request);

        // 幂等重放：同键同 payload 直接返回既有任务，不重复物化
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            ManagementTask existing = managementTaskService.findByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                String expectedHash = sha256(payload);
                if (!expectedHash.equals(existing.getIdempotencyPayloadHash())) {
                    throw new BatchConflictException(BatchReasonCode.IDEMPOTENCY_CONFLICT,
                            "幂等键 " + idempotencyKey + " 已存在但 payload 不匹配");
                }
                BatchPreviewResponse replay = preview(request);
                BatchCreateResponse resp = new BatchCreateResponse();
                resp.setTask(managementTaskService.getTask(existing.getId()));
                resp.setSelectedCount(replay.getSelectedCount());
                resp.setEligibleCount(replay.getEligibleCount());
                resp.setBlocked(replay.getBlocked());
                return resp;
            }
        }

        int limit = batchProperties.getMaxItems() + 1;
        List<Long> resolved = selectionResolver.resolve(request.getSelection(), limit);
        if (resolved.isEmpty()) {
            throw new BatchConflictException(BatchReasonCode.EMPTY_SELECTION, "筛选结果为空");
        }
        if (resolved.size() > batchProperties.getMaxItems()) {
            throw new BatchConflictException(BatchReasonCode.BATCH_SIZE_EXCEEDED,
                    "命中数量 " + resolved.size() + " 超过上限 " + batchProperties.getMaxItems());
        }

        BatchEligibilityChecker.Result eligibility = eligibilityChecker.evaluate(resolved, request.getOperation());
        List<Long> eligible = eligibility.eligible();

        // 危险操作：preview token 二次确认（过期/条件变化 → 409）
        if (DANGEROUS_OPS.contains(request.getOperation())) {
            if (request.getPreviewToken() == null || request.getPreviewToken().isBlank()) {
                throw new BatchConflictException(BatchReasonCode.PREVIEW_TOKEN_REQUIRED,
                        "危险操作必须携带 preview token 二次确认");
            }
            String invalidReason = previewTokenStore.validate(request, request.getPreviewToken(), eligible);
            if (invalidReason != null) {
                throw new BatchConflictException(invalidReason,
                        "preview 条件已变化或 token 过期，请重新确认");
            }
        }

        if (eligible.isEmpty()) {
            throw new BatchConflictException(BatchReasonCode.EMPTY_SELECTION, "无可执行的目标");
        }

        // 物化 items 快照：稳定排序（eligible 已按 id ASC）
        ManagementTaskResponse task = materializeTask(request, eligible, payload, idempotencyKey);

        // 执行：同步操作逐项执行；命令操作逐项入 Outbox 队列
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());
        if (SYNC_OPS.contains(request.getOperation())) {
            for (ManagementTaskItemResponse item : items) {
                metadataExecutor.execute(item.getId(), request.getPayload(), item.getTargetId());
            }
        } else if (COMMAND_OPS.contains(request.getOperation())) {
            for (ManagementTaskItemResponse item : items) {
                enqueueCommand(request.getOperation(), item);
            }
        }

        BatchCreateResponse resp = new BatchCreateResponse();
        resp.setTask(managementTaskService.getTask(task.getId()));
        resp.setSelectedCount(resolved.size());
        resp.setEligibleCount(eligible.size());
        resp.setBlocked(eligibility.blocked());
        return resp;
    }

    private ManagementTaskResponse materializeTask(BatchOperationRequest request,
                                                   List<Long> eligible, String payload,
                                                   String idempotencyKey) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(request.getOperation());
        req.setOperation(operationLabel(request.getOperation()));
        req.setTargetType("COMIC");
        req.setBatchId(UUID.randomUUID().toString());
        List<CreateManagementTaskRequest.TaskTarget> targets = new ArrayList<>();
        for (Long comicId : eligible) {
            CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
            target.setTargetType("COMIC");
            target.setTargetId(comicId);
            target.setOperationType(request.getOperation());
            targets.add(target);
        }
        req.setTargets(targets);
        return managementTaskService.createTask(req, idempotencyKey, payload);
    }

    private void enqueueCommand(TaskType operation, ManagementTaskItemResponse item) {
        ManagementCommandRequestedEvent event = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                operation.name(), "COMIC", item.getTargetId());
        outboxService.enqueue(event, EXCHANGE, ROUTING_REQUEST,
                item.getTaskId(), item.getId(), item.getAttempt());
        log.info("批量命令已入 Outbox: op={}, taskId={}, itemId={}, targetId={}",
                operation.name(), item.getTaskId(), item.getId(), item.getTargetId());
    }

    private static String operationLabel(TaskType operation) {
        return switch (operation) {
            case METADATA_UPDATE -> "批量更新元数据";
            case LQ_GENERATE -> "批量生成低清图";
            case LQ_REGENERATE -> "批量重生成低清图";
            case HQ_DELETE -> "批量删除高清图";
            case TRANSCODE -> "批量视频转码";
            case METADATA_REFRESH -> "批量刷新元数据";
            case COMIC_DELETE -> "批量回收漫画";
            case COMIC_RESTORE -> "批量恢复漫画";
            case COMIC_PURGE -> "批量永久清理";
            default -> "批量操作";
        };
    }

    private String toJson(BatchOperationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("批量请求序列化失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
