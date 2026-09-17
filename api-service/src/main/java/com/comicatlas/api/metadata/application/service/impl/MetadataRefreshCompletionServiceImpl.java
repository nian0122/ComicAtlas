package com.comicatlas.api.metadata.application.service.impl;

import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.metadata.application.port.out.MetadataRefreshCompletionPersistencePort;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.storage.application.port.in.ComicStatsService;
import com.comicatlas.api.outbox.application.port.in.InboxService;
import com.comicatlas.api.outbox.application.port.in.EventFingerprintService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.api.metadata.application.port.in.MetadataRefreshService.MetadataRefreshApplyResult;
import com.comicatlas.api.metadata.application.port.in.MetadataRefreshService.MetadataRefreshLoadRequest;
import com.comicatlas.api.media.application.port.in.HqMediaRegistrationService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.SnapshotUnavailableException;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.metadata.application.port.in.MetadataRefreshCompletionService;
import com.comicatlas.api.metadata.application.port.in.MetadataRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 元数据扫盘刷新完成专用流程（METADATA_REFRESH）。
 * <p>
 * 与通用 completed 分支的差异：快照读取/校验（loadAndValidate）必须在事务外，
 * 若塞入把 {@code business.run()} 整体包进事务的 generic 分支，文件 IO 将进入事务，
 * 且业务失败无法区分「快照不可信 → FAILED + ACK」与「基础设施故障 → DLQ」。
 * <p>
 * 流程：幂等前置检查（无事务）→ 事务外校验章节快照 → 成功短事务（item CAS
 * + 章节差异合并 + Inbox + 任务聚合）。最后一个 item 收尾时才重算整本统计、
 * 释放 comic REFRESHING 并写入一次元数据重导出 Outbox。
 * <p>
 * <b>幂等条件</b>：item 已终态 / attempt 不匹配 / 非 COMIC|CHAPTER·METADATA_REFRESH 直接 ACK；
 * 同 attempt 不同 eventId 的重复完成事件由 item CAS 竞争，只有胜者 apply。
 * <p>
 * <b>失败区分</b>：业务错误（摘要/schema/目标/数量/结构漂移）→ 独立短事务 item/task
 * FAILED、comic READY、Inbox 记录后 ACK 并保留快照；基础设施故障（DB/Outbox/
 * 快照文件不可用）→ 异常向上传播，由消费入口 reject 进 DLQ，不伪造成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRefreshCompletionServiceImpl implements MetadataRefreshCompletionService {
    // 元数据结果契约由应用服务公开，具体实现保持在元数据业务包内。

    /** 命令目标类型：漫画级（批量操作展开）。 */
    private static final String TARGET_TYPE_COMIC = "COMIC";
    /** 命令目标类型：章节级。 */
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";

    /** 暂存卷存储根键。 */
    private static final String ROOT_KEY_STAGING = "STAGING";

    /** 元数据刷新快照目录名（STAGING/metadata-refresh/{taskId}/{itemId}/{attempt}）。 */
    private static final String METADATA_REFRESH_DIR = "metadata-refresh/";

    private final MetadataRefreshCompletionPersistencePort persistencePort;
    private final MetadataRefreshService metadataRefreshService;
    private final HqMediaRegistrationService hqMediaRegistrationService;
    private final ComicStatsService comicStatsService;
    private final InboxService inboxService;
    private final OutboxService outboxService;
    private final ManagementTaskService managementTaskService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ApiStorageProperties apiStorageProperties;
    private final TransactionTemplate transactionTemplate;
    private final EventFingerprintService eventFingerprintService;

    public void handleCompleted(MetadataRefreshScanCompletedEvent ev) {
        String eventId = ev.eventId().toString();
        String payloadHash = eventFingerprintService.fingerprint(ev);

        // 1. 幂等前置检查（无事务）
        MetadataRefreshCompletionPersistencePort.ItemSnapshot item = persistencePort.findItem(ev.itemId());
        if (shouldSkip(ev, item, eventId)) {
            return;
        }
        Long comicId = resolveComicId(item.targetType(), item.targetId());

        // 2. 事务外读取并校验静态快照（SHA-256 + JSON 解析 + 结构校验）
        MetadataRefreshSnapshotDTO snapshot;
        try {
            snapshot = metadataRefreshService.loadAndValidate(
                    new MetadataRefreshLoadRequest(comicId, ev.snapshotRef(), ev.snapshotSha256(),
                            ev.snapshotBytes(), ev.schemaVersion()));
            validateTargetSnapshot(item, snapshot);
        } catch (BusinessException e) {
            if (isSnapshotIoFailure(e)) {
                throw e;
            }
            applyBusinessFailure(ev, item, comicId, eventId, payloadHash, e.getMessage());
            return;
        }

        // 3. 成功短事务；复核 databaseRevision 漂移抛 BusinessException → 整事务回滚 → 失败短事务
        Boolean applied;
        try {
            applied = transactionTemplate.execute(tx -> applySuccess(ev, comicId, eventId, payloadHash, snapshot));
        } catch (BusinessException e) {
            applyBusinessFailure(ev, item, comicId, eventId, payloadHash, e.getMessage());
            return;
        }

        // 4. 提交后删除当前 attempt 快照目录（删除失败仅记日志不失败）
        if (Boolean.TRUE.equals(applied)) {
            deleteSnapshotDir(ev);
        }
    }

    /** 元数据刷新完成事件幂等前置检查：命中任一条件直接 ACK（返回 true）。 */
    private boolean shouldSkip(MetadataRefreshScanCompletedEvent ev,
                               MetadataRefreshCompletionPersistencePort.ItemSnapshot item, String eventId) {
        if (item == null) {
            log.info("元数据刷新完成事件引用不存在的 item，忽略: itemId={}", ev.itemId());
            return true;
        }
        if (item.isTerminal()) {
            log.info("元数据刷新完成事件 item 已终态 {}，幂等跳过: itemId={}", item.status(), ev.itemId());
            return true;
        }
        if (item.attempt() != null && !item.attempt().equals(ev.attempt())) {
            log.info("元数据刷新完成事件 attempt 不匹配，忽略旧 attempt 结果: itemId={}, event={}, item={}",
                    ev.itemId(), ev.attempt(), item.attempt());
            return true;
        }
        boolean supportedTarget = TARGET_TYPE_COMIC.equals(item.targetType())
                || TARGET_TYPE_CHAPTER.equals(item.targetType());
        boolean metadataOperation = item.operationType() == TaskType.METADATA_REFRESH;
        boolean eventMatchesItem = Objects.equals(item.targetType(), ev.targetType())
                && Objects.equals(item.targetId(), ev.targetId())
                && metadataOperation
                && item.operationType().name().equals(ev.operationType());
        if (!metadataOperation || !supportedTarget || !eventMatchesItem) {
            log.warn("元数据刷新完成事件 target/op 不匹配，防御性忽略: itemId={}, op={}, target={}",
                    ev.itemId(), item.operationType(), item.targetType());
            return true;
        }
        return false;
    }

    /**
     * 成功短事务：comic 行锁 → item CAS → 章节差异合并 → Inbox → 按漫画收尾 → 任务聚合。
     * <p>
     * item CAS 影响行数 0 表示已被其他 eventId 处理（同 attempt 重复完成事件），
     * 幂等跳过 apply/Outbox/聚合，仅记录 Inbox 后返回 false（快照由胜者清理）。
     *
     * @return true 表示本事件是本次 attempt 的 CAS 胜者并已完整 apply
     */
    private boolean applySuccess(MetadataRefreshScanCompletedEvent ev, Long comicId,
                                 String eventId, String payloadHash,
                                 MetadataRefreshSnapshotDTO snapshot) {
        // 同一漫画的章节完成事务串行化，确保最后一个 item 能稳定观测 active=0。
        persistencePort.lockComic(comicId);

        // item CAS：当前 attempt 非终态 → SUCCEEDED；0 行 = 已被其他 eventId 处理 → 幂等跳过 apply
        LocalDateTime updateTime = LocalDateTime.now();
        int rows = persistencePort.markSucceededIfActive(
                ev.itemId(), ev.attempt(), updateTime, updateTime);
        if (rows == 0) {
            log.info("元数据刷新 item 已被其他 eventId 置为终态，幂等跳过 apply: itemId={}", ev.itemId());
            inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());
            return false;
        }

        // 先登记快照发现的新增 HQ 媒体，再执行刷新差异合并；两者同属本次刷新事务。
        HqMediaRegistrationService.HqMediaRegistrationResult registrationResult =
                hqMediaRegistrationService.registerValidatedSnapshot(snapshot);
        // 复核 databaseRevision 并执行差异合并（内部事务；漂移抛 BusinessException → 整体回滚 → 失败路径）
        MetadataRefreshApplyResult applyResult = metadataRefreshService.applyValidatedSnapshot(snapshot);
        log.info("元数据刷新已完成: comicId={}, registered={}, updated={}, discovered={}, missing={}",
                comicId, registrationResult.inserted(), applyResult.updated(), applyResult.discovered(),
                applyResult.missing());

        // 写 Inbox（eventId 幂等键）
        inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());

        // 任务状态聚合（item 到终态，全部完成则 task SUCCEEDED）——本次提交 = 管理任务成功点
        finalizeComicIfTaskComplete(comicId, ev.taskId(), ev.itemId(), ev.attempt());
        managementTaskService.reaggregateTask(ev.taskId());
        return true;
    }

    /**
     * 业务失败短事务：item/task → FAILED（记录 errorMessage）、Inbox 记录后 ACK，
     * 保留快照（供重试/排查）。同漫画所有项终态后才释放 REFRESHING。
     * <p>
     * 仅当前 attempt 且非终态时生效（CAS），不影响已成功的重复事件。事务内异常向上传播 → DLQ。
     */
    private void applyBusinessFailure(MetadataRefreshScanCompletedEvent ev,
                                      MetadataRefreshCompletionPersistencePort.ItemSnapshot item, Long comicId,
                                      String eventId, String payloadHash, String errorMessage) {
        log.warn("元数据刷新业务失败，置 FAILED 并 ACK: itemId={}, error={}", ev.itemId(), errorMessage);
        transactionTemplate.executeWithoutResult(tx -> {
            persistencePort.lockComic(comicId);
            LocalDateTime updateTime = LocalDateTime.now();
            persistencePort.markFailedIfActive(
                    ev.itemId(), ev.attempt(), errorMessage, updateTime, updateTime);
            inboxService.markProcessed(eventId, payloadHash, ev.taskId(), ev.itemId(), ev.attempt());
            finalizeComicIfTaskComplete(comicId, ev.taskId(), item.id(), ev.attempt());
            managementTaskService.reaggregateTask(ev.taskId());
        });
    }

    /** 通用 failed 事件已将 item 置为终态后，按所属漫画尝试整任务收尾。 */
    @Transactional
    public void handleCommandFailed(Long taskId, Long itemId, int attempt,
                                    String targetType, Long targetId) {
        Long comicId = resolveComicId(targetType, targetId);
        persistencePort.lockComic(comicId);
        finalizeComicIfTaskComplete(comicId, taskId, itemId, attempt);
    }

    private void finalizeComicIfTaskComplete(Long comicId, Long taskId, Long itemId, int attempt) {
        if (managementTaskService.countActiveMetadataItems(taskId, comicId) > 0) {
            return;
        }
        comicStatsService.refreshByComic(comicId);
        int releasedRows = persistencePort.markRefreshCompleted(comicId);
        if (releasedRows == 0) {
            return;
        }
        catalogCacheInvalidator.evict(comicId);
        outboxService.enqueue(new MetadataRefreshEvent(null, null, comicId),
                MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED,
                taskId, itemId, attempt);
    }

    private Long resolveComicId(String targetType, Long targetId) {
        if (TARGET_TYPE_COMIC.equals(targetType)) {
            return targetId;
        }
        if (TARGET_TYPE_CHAPTER.equals(targetType)) {
            Long comicId = persistencePort.findChapterComicId(targetId).orElse(null);
            if (comicId == null) {
                throw new BusinessException("元数据刷新章节不存在: " + targetId);
            }
            return comicId;
        }
        throw new BusinessException("元数据刷新目标类型不支持: " + targetType);
    }

    private void validateTargetSnapshot(MetadataRefreshCompletionPersistencePort.ItemSnapshot item,
                                        MetadataRefreshSnapshotDTO snapshot) {
        if (!TARGET_TYPE_CHAPTER.equals(item.targetType())) {
            return;
        }
        if (snapshot.chapters().size() != 1
                || !item.targetId().equals(snapshot.chapters().get(0).chapterId())) {
            throw new BusinessException("章节刷新快照与 item 目标不一致: " + item.targetId());
        }
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

}
