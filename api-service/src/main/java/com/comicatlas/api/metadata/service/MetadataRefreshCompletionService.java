package com.comicatlas.api.metadata.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.metadata.service.MetadataRefreshService.MetadataRefreshLoadRequest;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.common.exception.SnapshotUnavailableException;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * 元数据扫盘刷新完成专用流程（METADATA_REFRESH）。
 * <p>
 * 与通用 completed 分支的差异：快照读取/校验（loadAndValidate）必须在事务外，
 * 若塞入把 {@code business.run()} 整体包进事务的 generic 分支，文件 IO 将进入事务，
 * 且业务失败无法区分「快照不可信 → FAILED + ACK」与「基础设施故障 → DLQ」。
 * <p>
 * 流程：幂等前置检查（无事务）→ 事务外校验快照 → 成功短事务（comic 释放 + item CAS
 * + 差异合并 + Inbox + Outbox 入箱 + 任务聚合）→ 提交后清理快照。
 * <p>
 * <b>幂等条件</b>：item 已终态 / attempt 不匹配 / 非 COMIC·METADATA_REFRESH 直接 ACK；
 * 同 attempt 不同 eventId 的重复完成事件由 item CAS 竞争，只有胜者 apply。
 * <p>
 * <b>失败区分</b>：业务错误（摘要/schema/目标/数量/结构漂移）→ 独立短事务 item/task
 * FAILED、comic READY、Inbox 记录后 ACK 并保留快照；基础设施故障（DB/Outbox/
 * 快照文件不可用）→ 异常向上传播，由消费入口 reject 进 DLQ，不伪造成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRefreshCompletionService {

    /** 命令目标类型：漫画级（批量操作展开）。 */
    private static final String TARGET_TYPE_COMIC = "COMIC";

    /** 暂存卷存储根键。 */
    private static final String ROOT_KEY_STAGING = "STAGING";

    /** 元数据刷新快照目录名（STAGING/metadata-refresh/{taskId}/{itemId}/{attempt}）。 */
    private static final String METADATA_REFRESH_DIR = "metadata-refresh/";

    private final ManagementTaskItemMapper managementTaskItemMapper;
    private final ComicMapper comicMapper;
    private final MetadataRefreshService metadataRefreshService;
    private final InboxService inboxService;
    private final OutboxService outboxService;
    private final ManagementTaskService managementTaskService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ApiStorageProperties apiStorageProperties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public void handleCompleted(MetadataRefreshScanCompletedEvent ev) {
        String eventId = ev.eventId().toString();
        String payloadHash = sha256(toJson(ev));

        // 1. 幂等前置检查（无事务）
        ManagementTaskItem item = managementTaskItemMapper.selectById(ev.itemId());
        if (shouldSkip(ev, item, eventId)) {
            return;
        }
        Long comicId = item.getTargetId();

        // 2. 事务外读取并校验静态快照（SHA-256 + JSON 解析 + 结构校验）
        MetadataRefreshSnapshotDTO snapshot;
        try {
            snapshot = metadataRefreshService.loadAndValidate(
                    new MetadataRefreshLoadRequest(comicId, ev.snapshotRef(), ev.snapshotSha256(),
                            ev.snapshotBytes(), ev.schemaVersion()));
        } catch (BusinessException e) {
            if (isSnapshotIoFailure(e)) {
                throw e;
            }
            applyBusinessFailure(ev, eventId, payloadHash, e.getMessage());
            return;
        }

        // 3. 成功短事务；复核 databaseRevision 漂移抛 BusinessException → 整事务回滚 → 失败短事务
        Boolean applied;
        try {
            applied = transactionTemplate.execute(tx -> applySuccess(ev, eventId, payloadHash, snapshot));
        } catch (BusinessException e) {
            applyBusinessFailure(ev, eventId, payloadHash, e.getMessage());
            return;
        }

        // 4. 提交后删除当前 attempt 快照目录（删除失败仅记日志不失败）
        if (Boolean.TRUE.equals(applied)) {
            deleteSnapshotDir(ev);
        }
    }

    /** 元数据刷新完成事件幂等前置检查：命中任一条件直接 ACK（返回 true）。 */
    private boolean shouldSkip(MetadataRefreshScanCompletedEvent ev,
                               ManagementTaskItem item, String eventId) {
        if (item == null) {
            log.info("元数据刷新完成事件引用不存在的 item，忽略: itemId={}", ev.itemId());
            return true;
        }
        if (item.getStatus() != null && item.getStatus().isTerminal()) {
            log.info("元数据刷新完成事件 item 已终态 {}，幂等跳过: itemId={}", item.getStatus(), ev.itemId());
            return true;
        }
        if (item.getAttempt() != null && !item.getAttempt().equals(ev.attempt())) {
            log.info("元数据刷新完成事件 attempt 不匹配，忽略旧 attempt 结果: itemId={}, event={}, item={}",
                    ev.itemId(), ev.attempt(), item.getAttempt());
            return true;
        }
        if (item.getOperationType() != TaskType.METADATA_REFRESH || !TARGET_TYPE_COMIC.equals(item.getTargetType())) {
            log.warn("元数据刷新完成事件 target/op 不匹配，防御性忽略: itemId={}, op={}, target={}",
                    ev.itemId(), item.getOperationType(), item.getTargetType());
            return true;
        }
        return false;
    }

    /**
     * 成功短事务：comic 行锁 + CAS 释放 → item CAS → 差异合并 → Inbox → Outbox → 任务聚合。
     * <p>
     * item CAS 影响行数 0 表示已被其他 eventId 处理（同 attempt 重复完成事件），
     * 幂等跳过 apply/Outbox/聚合，仅记录 Inbox 后返回 false（快照由胜者清理）。
     *
     * @return true 表示本事件是本次 attempt 的 CAS 胜者并已完整 apply
     */
    private boolean applySuccess(MetadataRefreshScanCompletedEvent ev,
                                 String eventId, String payloadHash,
                                 MetadataRefreshSnapshotDTO snapshot) {
        Long comicId = snapshot.comicId();

        // 行锁读取 + CAS 释放 REFRESHING → READY（0 行视为并发已释放，继续不失败）
        Comic locked = comicMapper.selectByIdForUpdate(comicId);
        if (locked != null && locked.getStatus() == ComicStatus.REFRESHING) {
            comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                    .eq(Comic::getId, comicId)
                    .eq(Comic::getStatus, ComicStatus.REFRESHING)
                    .set(Comic::getStatus, ComicStatus.READY));
        }

        // item CAS：当前 attempt 非终态 → SUCCEEDED；0 行 = 已被其他 eventId 处理 → 幂等跳过 apply
        int rows = managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, ev.itemId())
                .eq(ManagementTaskItem::getAttempt, ev.attempt())
                .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                        ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                        ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.SUCCEEDED)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            log.info("元数据刷新 item 已被其他 eventId 置为终态，幂等跳过 apply: itemId={}", ev.itemId());
            inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());
            return false;
        }

        // 复核 databaseRevision 并执行差异合并（内部事务；漂移抛 BusinessException → 整体回滚 → 失败路径）
        metadataRefreshService.applyValidatedSnapshot(snapshot);
        catalogCacheInvalidator.evict(comicId);

        // 写 Inbox（eventId 幂等键）
        inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());

        // metadata 重导出走 Outbox（DB→JSON，relay 后发 MQ）；禁止 MediaMetadataSyncService 吞异常的 direct publish
        outboxService.enqueue(new MetadataRefreshEvent(null, null, comicId),
                MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED,
                ev.taskId(), ev.itemId(), ev.attempt());

        // 任务状态聚合（item 到终态，全部完成则 task SUCCEEDED）——本次提交 = 管理任务成功点
        managementTaskService.reaggregateTask(ev.taskId());
        return true;
    }

    /**
     * 业务失败短事务：item/task → FAILED（记录 errorMessage）、comic REFRESHING → READY、
     * Inbox 记录后 ACK，保留快照（供重试/排查）。
     * <p>
     * 仅当前 attempt 且非终态时生效（CAS），不影响已成功的重复事件。事务内异常向上传播 → DLQ。
     */
    private void applyBusinessFailure(MetadataRefreshScanCompletedEvent ev,
                                      String eventId, String payloadHash, String errorMessage) {
        log.warn("元数据刷新业务失败，置 FAILED 并 ACK: itemId={}, error={}", ev.itemId(), errorMessage);
        transactionTemplate.executeWithoutResult(tx -> {
            managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getId, ev.itemId())
                    .eq(ManagementTaskItem::getAttempt, ev.attempt())
                    .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                            ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                            ManagementTaskStatus.FAILED)
                    .set(ManagementTaskItem::getStatus, ManagementTaskStatus.FAILED)
                    .set(ManagementTaskItem::getErrorMessage, errorMessage)
                    .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                    .set(ManagementTaskItem::getLockKey, null)
                    .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
            releaseComicRefreshing(ev.targetId());
            managementTaskService.reaggregateTask(ev.taskId());
            inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());
        });
    }

    /** 提交后删除当前 attempt 快照目录（STAGING/metadata-refresh/{taskId}/{itemId}/{attempt}）。 */
    private void deleteSnapshotDir(MetadataRefreshScanCompletedEvent ev) {
        Path dir = apiStorageProperties.root(ROOT_KEY_STAGING).resolve(
                METADATA_REFRESH_DIR + ev.taskId() + "/" + ev.itemId() + "/" + ev.attempt());
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除元数据刷新快照文件失败: {}", path, e);
                }
            });
            log.info("元数据刷新快照目录已清理: taskId={}, itemId={}, attempt={}",
                    ev.taskId(), ev.itemId(), ev.attempt());
        } catch (IOException e) {
            log.warn("元数据刷新快照目录清理失败: taskId={}, itemId={}", ev.taskId(), ev.itemId(), e);
        }
    }

    /**
     * 快照产物不可用（文件缺失/非常规文件/读取 IO 异常）视为基础设施故障 → DLQ；
     * 纯业务校验异常（SHA/schema/comicId/数量/结构漂移）走失败短事务。
     * <p>
     * 产物级故障由 {@link SnapshotUnavailableException} 类型直接标识，
     * IOException cause 兜底兼容旧链路，避免依赖错误消息文案匹配。
     */
    private boolean isSnapshotIoFailure(BusinessException e) {
        if (e instanceof SnapshotUnavailableException) {
            return true;
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** 释放漫画元数据刷新锁（REFRESHING → READY），仅仍为 REFRESHING 时生效（CAS）。 */
    public void releaseComicRefreshing(Long comicId) {
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comicId)
                .eq(Comic::getStatus, ComicStatus.REFRESHING)
                .set(Comic::getStatus, ComicStatus.READY));
    }

    private String toJson(MetadataRefreshScanCompletedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("元数据刷新完成事件序列化失败: " + event.eventId(), e);
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
