package com.comicatlas.api.media.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.metadata.service.MetadataRefreshService;
import com.comicatlas.api.metadata.service.MetadataRefreshService.MetadataRefreshLoadRequest;
import com.comicatlas.api.metadata.service.MetadataUpdateCoordinator;
import com.comicatlas.api.outbox.service.EventFingerprintService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.shared.exception.SnapshotUnavailableException;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.task.entity.ManagementTaskItem;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.event.MetadataRefreshScanCompletedEvent;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * HQ 媒体登记扫描完成处理器。
 * <p>
 * 扫描快照的读取在事务外完成，page 登记在短事务内完成。事件沿用元数据扫描快照契约，
 * 通过 operationType 与元数据刷新流程分流；登记完成后仅失效目录缓存并请求 metadata.json 重导出，
 * 不触发 LQ 生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HqMediaRegistrationCompletionService {

    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";
    private static final String OPERATION_TYPE = "HQ_MEDIA_REGISTER";

    private final ManagementTaskItemMapper managementTaskItemMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MetadataRefreshService metadataRefreshService;
    private final HqMediaRegistrationService registrationService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final MetadataUpdateCoordinator metadataUpdateCoordinator;
    private final InboxService inboxService;
    private final EventFingerprintService eventFingerprintService;
    private final ManagementTaskService managementTaskService;
    private final ApiStorageProperties apiStorageProperties;
    private final TransactionTemplate transactionTemplate;

    /** 处理 Worker 扫描完成事件。 */
    public void handleCompleted(MetadataRefreshScanCompletedEvent event) {
        String eventId = event.eventId().toString();
        String payloadHash = eventFingerprintService.fingerprint(event);
        ManagementTaskItem item = managementTaskItemMapper.selectById(event.itemId());
        if (shouldSkip(event, item)) {
            return;
        }
        Long comicId = resolveComicId(item.getTargetType(), item.getTargetId());

        MetadataRefreshSnapshotDTO snapshot;
        try {
            snapshot = metadataRefreshService.loadAndValidate(
                    new MetadataRefreshLoadRequest(comicId, event.snapshotRef(), event.snapshotSha256(),
                            event.snapshotBytes(), event.schemaVersion()));
            validateTargetSnapshot(item, snapshot);
        } catch (BusinessException exception) {
            if (isSnapshotIoFailure(exception)) {
                throw exception;
            }
            applyBusinessFailure(event, comicId, eventId, payloadHash, exception.getMessage());
            return;
        }

        Boolean applied;
        try {
            applied = transactionTemplate.execute(status -> applySuccess(
                    event, comicId, eventId, payloadHash, snapshot));
        } catch (BusinessException exception) {
            applyBusinessFailure(event, comicId, eventId, payloadHash, exception.getMessage());
            return;
        }
        if (Boolean.TRUE.equals(applied)) {
            deleteSnapshotDir(event);
            catalogCacheInvalidator.evict(comicId);
            metadataUpdateCoordinator.requestSync(comicId, event.taskId(), "HQ 媒体登记完成");
        }
    }

    private boolean shouldSkip(MetadataRefreshScanCompletedEvent event, ManagementTaskItem item) {
        if (item == null || item.getStatus() == null || item.getStatus().isTerminal()) {
            log.info("HQ 媒体登记完成事件 item 不存在或已终态，幂等跳过: itemId={}", event.itemId());
            return true;
        }
        boolean supportedTarget = TARGET_TYPE_COMIC.equals(item.getTargetType())
                || TARGET_TYPE_CHAPTER.equals(item.getTargetType());
        boolean eventMatchesItem = item.getOperationType() == TaskType.HQ_MEDIA_REGISTER
                && supportedTarget
                && Objects.equals(item.getTargetType(), event.targetType())
                && Objects.equals(item.getTargetId(), event.targetId())
                && Objects.equals(event.operationType(), OPERATION_TYPE)
                && Objects.equals(item.getAttempt(), event.attempt());
        if (!eventMatchesItem) {
            log.warn("HQ 媒体登记完成事件 target/op 不匹配，防御性忽略: itemId={}, op={}, target={}",
                    event.itemId(), event.operationType(), event.targetId());
            return true;
        }
        return false;
    }

    private boolean applySuccess(MetadataRefreshScanCompletedEvent event, Long comicId,
                                 String eventId, String payloadHash,
                                 MetadataRefreshSnapshotDTO snapshot) {
        Comic comic = comicMapper.selectByIdForUpdate(comicId);
        if (comic == null || comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException("漫画当前不是 READY，无法登记 HQ 媒体: " + comicId);
        }
        int rows = managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getId, event.itemId())
                .eq(ManagementTaskItem::getAttempt, event.attempt())
                .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                        ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                        ManagementTaskStatus.FAILED)
                .set(ManagementTaskItem::getStatus, ManagementTaskStatus.SUCCEEDED)
                .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                .set(ManagementTaskItem::getLockKey, null)
                .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            inboxService.markProcessed(eventId, payloadHash, event.taskId(), event.itemId(), event.attempt());
            return false;
        }

        HqMediaRegistrationService.HqMediaRegistrationResult result =
                registrationService.registerValidatedSnapshot(snapshot);
        inboxService.markProcessed(eventId, payloadHash, event.taskId(), event.itemId(), event.attempt());
        managementTaskService.reaggregateTask(event.taskId());
        log.info("HQ 媒体登记完成: comicId={}, inserted={}, skippedExisting={}, skippedInvalid={}",
                comicId, result.inserted(), result.skippedExisting(), result.skippedInvalid());
        return true;
    }

    private void applyBusinessFailure(MetadataRefreshScanCompletedEvent event, Long comicId,
                                      String eventId, String payloadHash, String errorMessage) {
        log.warn("HQ 媒体登记业务失败，置 FAILED 并 ACK: itemId={}, error={}", event.itemId(), errorMessage);
        transactionTemplate.executeWithoutResult(status -> {
            comicMapper.selectByIdForUpdate(comicId);
            managementTaskItemMapper.update(null, new LambdaUpdateWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getId, event.itemId())
                    .eq(ManagementTaskItem::getAttempt, event.attempt())
                    .notIn(ManagementTaskItem::getStatus, ManagementTaskStatus.CANCELLED,
                            ManagementTaskStatus.SUCCEEDED, ManagementTaskStatus.PARTIALLY_SUCCEEDED,
                            ManagementTaskStatus.FAILED)
                    .set(ManagementTaskItem::getStatus, ManagementTaskStatus.FAILED)
                    .set(ManagementTaskItem::getErrorMessage, errorMessage)
                    .set(ManagementTaskItem::getCompletedAt, LocalDateTime.now())
                    .set(ManagementTaskItem::getLockKey, null)
                    .set(ManagementTaskItem::getUpdatedAt, LocalDateTime.now()));
            inboxService.markProcessed(eventId, payloadHash, event.taskId(), event.itemId(), event.attempt());
            managementTaskService.reaggregateTask(event.taskId());
        });
    }

    private Long resolveComicId(String targetType, Long targetId) {
        if (TARGET_TYPE_COMIC.equals(targetType)) {
            return targetId;
        }
        if (TARGET_TYPE_CHAPTER.equals(targetType)) {
            Chapter chapter = chapterMapper.selectById(targetId);
            if (chapter != null && chapter.getComicId() != null) {
                return chapter.getComicId();
            }
        }
        throw new BusinessException("HQ 媒体登记目标不存在: " + targetType + ":" + targetId);
    }

    private void validateTargetSnapshot(ManagementTaskItem item, MetadataRefreshSnapshotDTO snapshot) {
        if (!TARGET_TYPE_CHAPTER.equals(item.getTargetType())) {
            return;
        }
        if (snapshot.chapters().size() != 1
                || !item.getTargetId().equals(snapshot.chapters().get(0).chapterId())) {
            throw new BusinessException("HQ 媒体登记快照与 item 目标不一致: " + item.getTargetId());
        }
    }

    private void deleteSnapshotDir(MetadataRefreshScanCompletedEvent event) {
        Path stagingRoot = apiStorageProperties.root(StorageRootKeys.STAGING).getPath().normalize();
        Path snapshotPath = stagingRoot.resolve(event.snapshotRef()).normalize();
        if (!snapshotPath.startsWith(stagingRoot) || !Files.isRegularFile(snapshotPath)) {
            return;
        }
        Path directory = snapshotPath.getParent();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("删除 HQ 媒体登记快照文件失败: {}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("删除 HQ 媒体登记快照目录失败: taskId={}, itemId={}",
                    event.taskId(), event.itemId(), exception);
        }
    }

    private boolean isSnapshotIoFailure(BusinessException exception) {
        if (exception instanceof SnapshotUnavailableException) {
            return true;
        }
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
