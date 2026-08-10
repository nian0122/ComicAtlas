package com.comicatlas.api.management.trash;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 回收站生命周期编排服务 — 漫画/章节/媒体 7 天回收、恢复、对账与永久清理。
 * <p>
 * 回收：实体 READY→TRASHING，API 基于 DB refs 创建不可变 TRASH 清单，发布命令；
 * Worker 按清单同卷移动（绝不覆盖）。恢复/清理命令携带 manifestTaskId 定位清单目录。
 * 永久清理只接受 TRASHED + 二次确认 token + 7 天保留期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashLifecycleService {

    public static final String PURGE_CONFIRM_TOKEN = "PURGE";
    public static final int RETENTION_DAYS = 7;

    private static final String EXCHANGE = MqExchanges.MANAGEMENT;
    private static final String ROUTING_REQUEST = MqRoutingKeys.COMMAND_REQUESTED;

    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ManagementTaskService managementTaskService;
    private final ManagementTaskItemMapper itemMapper;
    private final OutboxService outboxService;
    private final TrashManifestService trashManifestService;
    private final OperationPolicyService policyService;
    private final ApiStorageProperties storageProperties;

    // ======================== 回收 ========================

    @Transactional
    public OperationSubmitResultDTO trashComic(Long comicId, String idempotencyKey) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            OperationSubmitResultDTO existing = idempotencyHit(idempotencyKey, "comic-delete:" + comicId);
            if (existing != null) {
                return existing;
            }
        }
        requireAllowed(policyService.forComic(comicStatusName(comic)), OperationPolicyService.OP_DELETE,
                "漫画状态 " + comic.getStatus() + " 不可回收");
        ManagementStateMachine.validateComicTransition(comicStatusName(comic), "TRASHING");

        List<TrashManifestDTO.Entry> entries = List.of(
                entry("HQ", comicId.toString(), "hq/" + comicId),
                entry("LQ", comicId.toString(), "lq/" + comicId),
                entry("THUMBS", comicId.toString(), "thumbs/" + comicId),
                entry("METADATA", comicId + ".json", "metadata/" + comicId + ".json"));

        comic.setStatus(ComicStatus.TRASHING);
        comicMapper.updateById(comic);
        return createTrashTask("COMIC", comicId, TaskType.COMIC_DELETE, "回收漫画", entries,
                idempotencyKey, "comic-delete:" + comicId);
    }

    @Transactional
    public OperationSubmitResultDTO trashChapter(Long comicId, Long chapterId) {
        Chapter chapter = requireChapterInComic(comicId, chapterId);
        requireAllowed(policyService.forChapter(chapter.getStatus() == null ? null : chapter.getStatus().name()),
                OperationPolicyService.OP_DELETE,
                "章节状态 " + chapter.getStatus() + " 不可回收");
        ManagementStateMachine.validateChapterTransition(
                chapter.getStatus() == null ? null : chapter.getStatus().name(), "TRASHING");

        // 逐媒体使用 DB 真实 hqRoot/hqPath 与 lqRoot/lqPath 生成不可变清单，
        // 不按 globalOrder 猜目录、不做目录聚合（最终布局为 {comicId}/{chapterId}）
        List<TrashManifestDTO.Entry> entries = new ArrayList<>();
        List<Media> mediaList = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId));
        for (Media media : mediaList) {
            if (media.getHqPath() != null && !media.getHqPath().isBlank()) {
                entries.add(entry(hqRootKey(media), media.getHqPath(), "hq/" + media.getHqPath()));
            }
            if (media.getLqPath() != null && !media.getLqPath().isBlank()) {
                entries.add(entry(lqRootKey(media), media.getLqPath(), "lq/" + media.getLqPath()));
            }
        }

        chapter.setStatus(ChapterLifecycleStatus.TRASHING);
        chapterMapper.updateById(chapter);
        return createTrashTask("CHAPTER", chapterId, TaskType.CHAPTER_TRASH, "回收章节", entries,
                null, null);
    }

    @Transactional
    public OperationSubmitResultDTO trashMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "媒体不存在: " + mediaId);
        }
        requireAllowed(policyService.forMedia(mediaStatusName(media)), OperationPolicyService.OP_DELETE,
                "媒体状态 " + media.getStatus() + " 不可回收");
        ManagementStateMachine.validateMediaTransition(mediaStatusName(media), "TRASHING");

        List<TrashManifestDTO.Entry> entries = new ArrayList<>();
        if (media.getHqPath() != null && !media.getHqPath().isBlank()) {
            entries.add(entry("HQ", media.getHqPath(), "hq/" + media.getHqPath()));
        }

        // 释放页码槽位：回收期间 pageNumber = -id（唯一负值），原页码存入 original_page_number
        media.setStatus(MediaLifecycleStatus.TRASHING);
        media.setOriginalPageNumber(media.getPageNumber());
        media.setPageNumber(-media.getId().intValue());
        mediaMapper.updateById(media);
        return createTrashTask("MEDIA", mediaId, TaskType.MEDIA_TRASH, "回收媒体", entries,
                null, null);
    }

    // ======================== 恢复 ========================

    @Transactional
    public OperationSubmitResultDTO restoreComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        requireAllowed(policyService.forComic(comicStatusName(comic)), OperationPolicyService.OP_RECOVER,
                "漫画状态 " + comic.getStatus() + " 不可恢复");
        ManagementStateMachine.validateComicTransition(comicStatusName(comic), "RESTORING");
        Long manifestTaskId = findTrashTaskId("COMIC", comicId);

        comic.setStatus(ComicStatus.RESTORING);
        comicMapper.updateById(comic);
        return createCommandTask("COMIC", comicId, TaskType.COMIC_RESTORE, "恢复漫画", manifestTaskId);
    }

    @Transactional
    public OperationSubmitResultDTO restoreChapter(Long comicId, Long chapterId) {
        Chapter chapter = requireChapterInComic(comicId, chapterId);
        requireAllowed(policyService.forChapter(chapter.getStatus() == null ? null : chapter.getStatus().name()),
                OperationPolicyService.OP_RECOVER,
                "章节状态 " + chapter.getStatus() + " 不可恢复");
        ManagementStateMachine.validateChapterTransition(
                chapter.getStatus() == null ? null : chapter.getStatus().name(), "RESTORING");
        Long manifestTaskId = findTrashTaskId("CHAPTER", chapterId);

        chapter.setStatus(ChapterLifecycleStatus.RESTORING);
        chapterMapper.updateById(chapter);
        return createCommandTask("CHAPTER", chapterId, TaskType.CHAPTER_RESTORE, "恢复章节", manifestTaskId);
    }

    @Transactional
    public OperationSubmitResultDTO restoreMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "媒体不存在: " + mediaId);
        }
        requireAllowed(policyService.forMedia(mediaStatusName(media)), OperationPolicyService.OP_RECOVER,
                "媒体状态 " + media.getStatus() + " 不可恢复");
        ManagementStateMachine.validateMediaTransition(mediaStatusName(media), "RESTORING");
        Long manifestTaskId = findTrashTaskId("MEDIA", mediaId);

        media.setStatus(MediaLifecycleStatus.RESTORING);
        mediaMapper.updateById(media);
        return createCommandTask("MEDIA", mediaId, TaskType.MEDIA_RESTORE, "恢复媒体", manifestTaskId);
    }

    // ======================== 永久清理 ========================

    @Transactional
    public OperationSubmitResultDTO purgeComic(Long comicId, String token) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        return purge("COMIC", comicId, token,
                () -> {
                    requireAllowed(policyService.forComic(comicStatusName(comic)), OperationPolicyService.OP_PURGE,
                            "漫画状态 " + comic.getStatus() + " 不可永久清理");
                    ManagementStateMachine.validateComicTransition(comicStatusName(comic), "PURGING");
                    checkRetention(comic.getTrashedAt());
                    comic.setStatus(ComicStatus.PURGING);
                    comicMapper.updateById(comic);
                },
                TaskType.COMIC_PURGE, "永久清理漫画");
    }

    @Transactional
    public OperationSubmitResultDTO purgeChapter(Long comicId, Long chapterId, String token) {
        Chapter chapter = requireChapterInComic(comicId, chapterId);
        return purge("CHAPTER", chapterId, token,
                () -> {
                    requireAllowed(policyService.forChapter(chapter.getStatus() == null ? null : chapter.getStatus().name()),
                            OperationPolicyService.OP_PURGE,
                            "章节状态 " + chapter.getStatus() + " 不可永久清理");
                    ManagementStateMachine.validateChapterTransition(
                            chapter.getStatus() == null ? null : chapter.getStatus().name(), "PURGING");
                    checkRetention(chapter.getTrashedAt());
                    chapter.setStatus(ChapterLifecycleStatus.PURGING);
                    chapterMapper.updateById(chapter);
                },
                TaskType.CHAPTER_PURGE, "永久清理章节");
    }

    @Transactional
    public OperationSubmitResultDTO purgeMedia(Long mediaId, String token) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "媒体不存在: " + mediaId);
        }
        return purge("MEDIA", mediaId, token,
                () -> {
                    requireAllowed(policyService.forMedia(mediaStatusName(media)), OperationPolicyService.OP_PURGE,
                            "媒体状态 " + media.getStatus() + " 不可永久清理");
                    ManagementStateMachine.validateMediaTransition(mediaStatusName(media), "PURGING");
                    checkRetention(media.getTrashedAt());
                    media.setStatus(MediaLifecycleStatus.PURGING);
                    mediaMapper.updateById(media);
                },
                TaskType.MEDIA_PURGE, "永久清理媒体");
    }

    private OperationSubmitResultDTO purge(String targetType, Long targetId, String token,
                                        Runnable precondition, TaskType operation, String operationLabel) {
        if (token == null || !PURGE_CONFIRM_TOKEN.equals(token)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "二次确认 token 不匹配，必须为 " + PURGE_CONFIRM_TOKEN);
        }
        precondition.run();
        Long manifestTaskId = findTrashTaskId(targetType, targetId);
        return createCommandTask(targetType, targetId, operation, operationLabel, manifestTaskId);
    }

    // ======================== 对账 ========================

    /** 生成对账报告（只读）。 */
    public TrashReconcileReport reconcile(String targetType, Long targetId) {
        Long taskId = findTrashTaskId(targetType, targetId);
        String dbStatus = resolveDbStatus(targetType, targetId);
        TrashManifestDTO manifest = taskId != null
                ? trashManifestService.readManifest(targetType, targetId, taskId) : null;
        TrashManifestItemDTO actual = taskId != null
                ? trashManifestService.readActual(targetType, targetId, taskId) : null;

        List<TrashReconcileReport.EntryReport> entries = new ArrayList<>();
        if (manifest != null) {
            for (TrashManifestDTO.Entry e : manifest.entries()) {
                boolean sourceExists = existsInRoot(e.rootKey(), e.sourceRelativePath());
                boolean trashExists = existsInTrash(targetType, targetId, taskId, e.trashRelativePath());
                String state = trashExists ? (sourceExists ? "BOTH" : "IN_TRASH")
                        : (sourceExists ? "AT_SOURCE" : "MISSING");
                entries.add(new TrashReconcileReport.EntryReport(
                        e.rootKey(), e.sourceRelativePath(), sourceExists, trashExists, state));
            }
        }
        boolean consistent = computeConsistency(dbStatus, actual, entries);
        return new TrashReconcileReport(targetType, targetId, dbStatus, taskId,
                actual != null ? actual.status() : null, consistent, entries);
    }

    /** 对账并修复可安全自动恢复的 DB 状态（迟到的结果事件恢复）。 */
    @Transactional
    public TrashReconcileReport reconcileAndRepair(String targetType, Long targetId) {
        Long taskId = findTrashTaskId(targetType, targetId);
        TrashManifestItemDTO actual = taskId != null
                ? trashManifestService.readActual(targetType, targetId, taskId) : null;
        if (actual == null) {
            return reconcile(targetType, targetId);
        }
        String dbStatus = resolveDbStatus(targetType, targetId);
        String repaired = null;
        if (TrashManifestItemDTO.STATUS_TRASHED.equals(actual.status()) && "TRASHING".equals(dbStatus)) {
            if (markTrashed(targetType, targetId)) {
                repaired = "TRASHED";
            }
        } else if (TrashManifestItemDTO.STATUS_COMPENSATED.equals(actual.status()) && "TRASHING".equals(dbStatus)) {
            if (markReady(targetType, targetId)) {
                repaired = "READY";
            }
        }
        if (repaired != null) {
            log.info("对账修复: {}/{} -> {}", targetType, targetId, repaired);
        }
        return reconcile(targetType, targetId);
    }

    private boolean markTrashed(String targetType, Long targetId) {
        switch (targetType) {
            case "COMIC" -> {
                Comic comic = comicMapper.selectById(targetId);
                if (comic != null && comic.getStatus() == ComicStatus.TRASHING) {
                    comic.setStatus(ComicStatus.TRASHED);
                    comic.setTrashedAt(LocalDateTime.now());
                    comicMapper.updateById(comic);
                    return true;
                }
            }
            case "CHAPTER" -> {
                Chapter chapter = chapterMapper.selectById(targetId);
                if (chapter != null && chapter.getStatus() == ChapterLifecycleStatus.TRASHING) {
                    chapter.setStatus(ChapterLifecycleStatus.TRASHED);
                    chapter.setTrashedAt(LocalDateTime.now());
                    chapterMapper.updateById(chapter);
                    return true;
                }
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                if (media != null && media.getStatus() == MediaLifecycleStatus.TRASHING) {
                    media.setStatus(MediaLifecycleStatus.TRASHED);
                    media.setTrashedAt(LocalDateTime.now());
                    mediaMapper.updateById(media);
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }

    private boolean markReady(String targetType, Long targetId) {
        switch (targetType) {
            case "COMIC" -> {
                Comic comic = comicMapper.selectById(targetId);
                if (comic != null && comic.getStatus() == ComicStatus.TRASHING) {
                    comic.setStatus(ComicStatus.READY);
                    comic.setTrashedAt(null);
                    comicMapper.updateById(comic);
                    return true;
                }
            }
            case "CHAPTER" -> {
                Chapter chapter = chapterMapper.selectById(targetId);
                if (chapter != null && chapter.getStatus() == ChapterLifecycleStatus.TRASHING) {
                    chapter.setStatus(ChapterLifecycleStatus.READY);
                    chapter.setTrashedAt(null);
                    chapterMapper.updateById(chapter);
                    return true;
                }
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                if (media != null && media.getStatus() == MediaLifecycleStatus.TRASHING) {
                    media.setStatus(MediaLifecycleStatus.READY);
                    media.setTrashedAt(null);
                    media.setPageNumber(media.getOriginalPageNumber());
                    mediaMapper.updateById(media);
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }

    private boolean computeConsistency(String dbStatus, TrashManifestItemDTO actual,
                                       List<TrashReconcileReport.EntryReport> entries) {
        boolean conflict = entries.stream().anyMatch(e -> "BOTH".equals(e.state()));
        if (conflict) {
            return false;
        }
        if (actual == null) {
            // 无实际结果：TRASHING 且全部在源位置可视为待执行，一致
            return "TRASHING".equals(dbStatus);
        }
        return switch (actual.status()) {
            case TrashManifestItemDTO.STATUS_TRASHED, TrashManifestItemDTO.STATUS_PURGED
                    -> "TRASHED".equals(dbStatus) || "PURGING".equals(dbStatus);
            case TrashManifestItemDTO.STATUS_COMPENSATED, TrashManifestItemDTO.STATUS_RESTORED
                    -> "READY".equals(dbStatus);
            case TrashManifestItemDTO.STATUS_PARTIAL -> "TRASHING".equals(dbStatus);
            default -> false;
        };    }

    private String resolveDbStatus(String targetType, Long targetId) {
        return switch (targetType) {
            case "COMIC" -> {
                Comic comic = comicMapper.selectById(targetId);
                yield comic == null || comic.getStatus() == null ? null : comic.getStatus().name();
            }
            case "CHAPTER" -> {
                Chapter chapter = chapterMapper.selectById(targetId);
                yield chapter == null || chapter.getStatus() == null ? null : chapter.getStatus().name();
            }
            case "MEDIA" -> {
                Media media = mediaMapper.selectById(targetId);
                yield media == null ? null : mediaStatusName(media);
            }
            default -> null;
        };
    }

    private boolean existsInRoot(String rootKey, String relative) {
        ApiStorageRoot root = storageProperties.getRoots().get(rootKey);
        if (root == null || !root.isEnabled()) {
            return false;
        }
        try {
            return Files.exists(root.resolve(relative));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean existsInTrash(String targetType, Long targetId, Long taskId, String trashRelative) {
        try {
            return Files.exists(trashManifestService.manifestDir(targetType, targetId, taskId)
                    .resolve(trashRelative));
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 内部辅助 ========================

    private OperationSubmitResultDTO createTrashTask(String targetType, Long targetId, TaskType operation,
                                                   String operationLabel, List<TrashManifestDTO.Entry> entries,
                                                   String idempotencyKey, String payload) {
        ManagementTaskResponse task = createTask(operation, operationLabel, targetType, targetId, idempotencyKey, payload);
        Long taskId = task.getId();
        trashManifestService.writeManifest(new TrashManifestDTO(
                TrashManifestDTO.CURRENT_VERSION, targetType, targetId, taskId, Instant.now(), entries));
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(taskId);
        for (ManagementTaskItemResponse item : items) {
            enqueueCommand(operation, item, targetType, targetId, null);
        }
        log.info("回收命令已提交: {}/{} taskId={}, entries={}", targetType, targetId, taskId, entries.size());
        return OperationSubmitResultDTO.of(taskId, operation.name(), task.getStatus().name(), items.size());
    }

    private OperationSubmitResultDTO createCommandTask(String targetType, Long targetId, TaskType operation,
                                                    String operationLabel, Long manifestTaskId) {
        if (manifestTaskId == null) {
            throw new ConflictException("未找到 " + targetType + ":" + targetId + " 的回收清单，无法执行 " + operation);
        }
        ManagementTaskResponse task = createTask(operation, operationLabel, targetType, targetId, null, null);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());
        for (ManagementTaskItemResponse item : items) {
            itemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ManagementTaskItem>()
                    .eq(ManagementTaskItem::getId, item.getId())
                    .set(ManagementTaskItem::getResultRefType, "TRASH_MANIFEST")
                    .set(ManagementTaskItem::getResultRefId, manifestTaskId));
            enqueueCommand(operation, item, targetType, targetId, manifestTaskId);
        }
        log.info("命令已提交: {}/{} taskId={}, manifestTaskId={}", targetType, targetId, task.getId(), manifestTaskId);
        return OperationSubmitResultDTO.of(task.getId(), operation.name(), task.getStatus().name(), items.size());
    }

    private ManagementTaskResponse createTask(TaskType operation, String operationLabel, String targetType,
                                              Long targetId, String idempotencyKey, String payload) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(operation);
        req.setOperation(operationLabel);
        req.setTargetType(targetType);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(targetType);
        target.setTargetId(targetId);
        target.setOperationType(operation);
        req.setTargets(List.of(target));
        return managementTaskService.createTask(req, idempotencyKey, payload);
    }

    private void enqueueCommand(TaskType operation, ManagementTaskItemResponse item,
                                String targetType, Long targetId, Long manifestTaskId) {
        outboxService.enqueue(new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                operation.name(), targetType, targetId, manifestTaskId),
                EXCHANGE, ROUTING_REQUEST,
                item.getTaskId(), item.getId(), item.getAttempt());
    }

    /** 幂等命中检查：同键同 payload 返回已有任务结果。 */
    private OperationSubmitResultDTO idempotencyHit(String idempotencyKey, String payload) {
        ManagementTask existing = managementTaskService.findByIdempotencyKey(idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (!sha256(payload).equals(existing.getIdempotencyPayloadHash())) {
            throw new ConflictException("幂等键 " + idempotencyKey + " 已存在但 payload 不匹配");
        }
        return OperationSubmitResultDTO.of(existing.getId(), existing.getTaskType().name(),
                existing.getStatus().name(), existing.getTotalCount());
    }

    /** 查找目标最近一次回收任务的 taskId（作为清单目录定位）。 */
    private Long findTrashTaskId(String targetType, Long targetId) {
        TaskType trashOp = switch (targetType) {
            case "COMIC" -> TaskType.COMIC_DELETE;
            case "CHAPTER" -> TaskType.CHAPTER_TRASH;
            case "MEDIA" -> TaskType.MEDIA_TRASH;
            default -> throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "未知目标类型: " + targetType);
        };
        List<ManagementTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTargetType, targetType)
                .eq(ManagementTaskItem::getTargetId, targetId)
                .eq(ManagementTaskItem::getOperationType, trashOp)
                .orderByDesc(ManagementTaskItem::getId)
                .last("LIMIT 1"));
        return items.isEmpty() ? null : items.get(0).getTaskId();
    }

    private Chapter requireChapterInComic(Long comicId, Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        if (!chapter.getComicId().equals(comicId)) {
            throw new ConflictException("章节不属于该漫画");
        }
        return chapter;
    }

    private static String comicStatusName(Comic comic) {
        return comic.getStatus() == null ? null : comic.getStatus().name();
    }

    private static String mediaStatusName(Media media) {
        return media.getStatus() == null ? null : media.getStatus().name();
    }

    private void requireAllowed(AllowedOperations ops, String op, String message) {
        if (!ops.isAllowed(op)) {
            String reason = ops.blockedReasons().getOrDefault(op, ops.blockedReasons().getOrDefault("*", message));
            throw new ConflictException(message + "：" + reason);
        }
    }

    private void checkRetention(LocalDateTime trashedAt) {
        if (trashedAt == null) {
            return; // 旧数据无保留期信息，允许清理
        }
        LocalDateTime deadline = trashedAt.plusDays(RETENTION_DAYS);
        if (LocalDateTime.now().isBefore(deadline)) {
            throw new ConflictException("未到 7 天保留期（" + trashedAt + " + 7 天），暂不可永久清理");
        }
    }

    private static TrashManifestDTO.Entry entry(String rootKey, String source, String trash) {
        return new TrashManifestDTO.Entry(rootKey, source, trash);
    }

    private static String hqRootKey(Media media) {
        return media.getHqRoot() != null && !media.getHqRoot().isBlank() ? media.getHqRoot() : "HQ";
    }

    private static String lqRootKey(Media media) {
        return media.getLqRoot() != null && !media.getLqRoot().isBlank() ? media.getLqRoot() : "LQ";
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
