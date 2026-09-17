package com.comicatlas.api.media.application.service.impl;

import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.interfaces.rest.dto.OperationSubmitResultDTO;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.trash.application.port.in.TrashLifecycleService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.media.video.VideoPlayability;
import com.comicatlas.api.media.application.port.in.MediaOperationCommandService;
import com.comicatlas.api.media.application.port.out.MediaCommandPersistencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 媒体操作命令编排服务。
 * <p>
 * 将 LQ 生成 / HQ 删除 / 视频转码 / 元数据刷新 / 整本删除统一为
 * 创建 ManagementTask（target lock 防冲突）+ 同事务 Outbox 发布
 * ManagementCommandRequestedEvent，Worker 消费命令后回传进度/结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaOperationCommandServiceImpl implements MediaOperationCommandService {
    // 媒体命令契约由应用服务公开，具体实现保持在媒体业务包内。

    private final MediaCommandPersistencePort persistencePort;
    private final ManagementTaskService managementTaskService;
    private final OutboxService outboxService;
    private final TrashLifecycleService trashLifecycleService;

    private static final String EXCHANGE = MqExchanges.MANAGEMENT;
    private static final String ROUTING_REQUEST = MqRoutingKeys.COMMAND_REQUESTED;

    // ======================== LQ 生成 ========================

    public OperationSubmitResultDTO requestLqForComic(Long comicId, boolean regenerate) {
        List<MediaCommandPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId);
        TaskType operation = regenerate ? TaskType.LQ_REGENERATE : TaskType.LQ_GENERATE;

        List<CreateManagementTaskRequest.TaskTarget> targets = new ArrayList<>();
        for (MediaCommandPersistencePort.ChapterSnapshot chapter : chapters) {
            List<MediaCommandPersistencePort.MediaSnapshot> eligible = eligibleLqPages(chapter.id(), regenerate);
            if (!eligible.isEmpty()) {
                targets.add(target("CHAPTER", chapter.id(), operation));
            }
        }
        if (targets.isEmpty()) {
            log.info("漫画 {} 无待生成 LQ 的章节，跳过", comicId);
            return OperationSubmitResultDTO.of(null, operation.name(), null, 0);
        }

        ManagementTaskResponse task = createTask(operation, "生成低质量图片", "COMIC", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            markLqQueued(item.getTargetId());
            enqueue(operation, item, "CHAPTER", item.getTargetId());
        }
        log.info("LQ 命令已提交: comicId={}, regenerate={}, taskId={}, items={}",
                comicId, regenerate, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), operation.name(), task.getStatus().name(), items.size());
    }

    public OperationSubmitResultDTO requestLqForChapter(Long chapterId, boolean regenerate) {
        MediaCommandPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        TaskType operation = regenerate ? TaskType.LQ_REGENERATE : TaskType.LQ_GENERATE;

        List<MediaCommandPersistencePort.MediaSnapshot> eligible = eligibleLqPages(chapterId, regenerate);
        if (eligible.isEmpty()) {
            log.info("章节 {} 无待生成 LQ 的页面，跳过", chapterId);
            return OperationSubmitResultDTO.of(null, operation.name(), null, 0);
        }

        ManagementTaskResponse task = createTask(operation, "生成低质量图片", "CHAPTER",
                List.of(target("CHAPTER", chapterId, operation)));
        ManagementTaskItemResponse item = managementTaskService.getTaskItems(task.getId()).get(0);

        markLqQueued(chapterId);
        enqueue(operation, item, "CHAPTER", chapterId);
        log.info("LQ 命令已提交: chapterId={}, regenerate={}, taskId={}",
                chapterId, regenerate, task.getId());
        return OperationSubmitResultDTO.of(task.getId(), operation.name(), task.getStatus().name(), 1);
    }

    private List<MediaCommandPersistencePort.MediaSnapshot> eligibleLqPages(Long chapterId, boolean regenerate) {
        List<MediaCommandPersistencePort.MediaSnapshot> mediaItems = persistencePort.findImages(chapterId);
        return mediaItems.stream()
                .filter(media -> media.hqStatus() != HqStatus.DELETED)
                .filter(media -> regenerate || media.lqStatus() != LqStatus.READY)
                .toList();
    }

    private void markLqQueued(Long chapterId) {
        persistencePort.markLqQueued(chapterId);
    }

    // ======================== HQ 删除 ========================

    public OperationSubmitResultDTO requestHqDeleteForComic(Long comicId) {
        List<MediaCommandPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId);
        if (chapters.isEmpty()) {
            log.info("漫画 {} 无章节，跳过", comicId);
            return OperationSubmitResultDTO.of(null, TaskType.HQ_DELETE.name(), null, 0);
        }
        List<Long> chapterIds = chapters.stream().map(MediaCommandPersistencePort.ChapterSnapshot::id).toList();

        // 一次性 IN 查询取回全部候选图片页并按章节分组，避免逐章 selectCount/selectList（N+1）
        List<MediaCommandPersistencePort.MediaSnapshot> deletablePages = persistencePort.findDeletableImages(chapterIds);
        Map<Long, List<MediaCommandPersistencePort.MediaSnapshot>> pagesByChapter = deletablePages.stream()
                .collect(Collectors.groupingBy(MediaCommandPersistencePort.MediaSnapshot::chapterId));

        List<CreateManagementTaskRequest.TaskTarget> targets = new ArrayList<>();
        for (MediaCommandPersistencePort.ChapterSnapshot chapter : chapters) {
            List<MediaCommandPersistencePort.MediaSnapshot> mediaItems = pagesByChapter.getOrDefault(chapter.id(), List.of());
            if (mediaItems.isEmpty()) {
                continue;
            }
            validateHqDeletePrecondition(mediaItems);
            targets.add(target("CHAPTER", chapter.id(), TaskType.HQ_DELETE));
        }
        if (targets.isEmpty()) {
            log.info("漫画 {} 无可删除 HQ 的章节，跳过", comicId);
            return OperationSubmitResultDTO.of(null, TaskType.HQ_DELETE.name(), null, 0);
        }

        ManagementTaskResponse task = createTask(TaskType.HQ_DELETE, "删除高清图片", "COMIC", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        List<Long> targetChapterIds = items.stream()
                .map(ManagementTaskItemResponse::getTargetId)
                .toList();
        markHqDeleteQueued(targetChapterIds);
        for (ManagementTaskItemResponse item : items) {
            enqueue(TaskType.HQ_DELETE, item, "CHAPTER", item.getTargetId());
        }
        log.info("HQ 删除命令已提交: comicId={}, taskId={}, items={}",
                comicId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.HQ_DELETE.name(), task.getStatus().name(), items.size());
    }

    public OperationSubmitResultDTO requestHqDeleteForChapter(Long chapterId) {
        MediaCommandPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        if (!hasDeletableHq(chapterId)) {
            log.info("章节 {} 无可删除 HQ，跳过", chapterId);
            return OperationSubmitResultDTO.of(null, TaskType.HQ_DELETE.name(), null, 0);
        }
        validateHqDeletePrecondition(chapterId);

        ManagementTaskResponse task = createTask(TaskType.HQ_DELETE, "删除高清图片", "CHAPTER",
                List.of(target("CHAPTER", chapterId, TaskType.HQ_DELETE)));
        ManagementTaskItemResponse item = managementTaskService.getTaskItems(task.getId()).get(0);

        markHqDeleteQueued(chapterId);
        enqueue(TaskType.HQ_DELETE, item, "CHAPTER", chapterId);
        log.info("HQ 删除命令已提交: chapterId={}, taskId={}", chapterId, task.getId());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.HQ_DELETE.name(), task.getStatus().name(), 1);
    }

    private boolean hasDeletableHq(Long chapterId) {
        return persistencePort.countDeletableImages(chapterId) > 0;
    }

    /**
     * HQ 删除前置条件：全部图片页 LQ 必须 READY。
     */
    private void validateHqDeletePrecondition(Long chapterId) {
        List<MediaCommandPersistencePort.MediaSnapshot> mediaItems = persistencePort.findDeletableImages(List.of(chapterId));
        validateHqDeletePrecondition(mediaItems);
    }

    /**
     * HQ 删除前置条件（复用已加载页数据）：全部图片页 LQ 必须 READY。
     */
    private void validateHqDeletePrecondition(List<MediaCommandPersistencePort.MediaSnapshot> mediaItems) {
        List<MediaCommandPersistencePort.MediaSnapshot> notReady = mediaItems.stream()
                .filter(media -> media.lqStatus() != LqStatus.READY)
                .toList();
        if (!notReady.isEmpty()) {
            List<String> details = notReady.stream()
                    .map(media -> String.format("第 %d 页 (pageId=%d, lqStatus=%s)",
                            media.pageNumber(), media.id(), media.lqStatus()))
                    .toList();
            throw new ConflictException("HQ 删除前置条件不满足：以下页面 LQ 未就绪 -> " + details);
        }
    }

    private void markHqDeleteQueued(Long chapterId) {
        persistencePort.markHqDeleteQueued(chapterId);
    }

    private void markHqDeleteQueued(List<Long> chapterIds) {
        if (chapterIds.isEmpty()) {
            return;
        }
        persistencePort.markHqDeleteQueued(chapterIds);
    }

    // ======================== 视频转码 ========================

    private static final Set<TranscodeStatus> ACTIVE_TRANSCODE =
            Set.of(TranscodeStatus.QUEUED, TranscodeStatus.TRANSCODING);

    public OperationSubmitResultDTO requestTranscodeForComic(Long comicId) {
        List<MediaCommandPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId);
        if (chapters.isEmpty()) {
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }
        List<Long> chapterIds = chapters.stream().map(MediaCommandPersistencePort.ChapterSnapshot::id).toList();

        List<MediaCommandPersistencePort.MediaSnapshot> toTranscode = persistencePort.findVideosByChapters(chapterIds);
        List<MediaCommandPersistencePort.MediaSnapshot> eligible = toTranscode.stream()
                .filter(this::isTranscodeEligible)
                .toList();
        if (eligible.isEmpty()) {
            log.info("漫画 {} 无待转码视频，跳过", comicId);
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        List<CreateManagementTaskRequest.TaskTarget> targets = eligible.stream()
                .map(media -> target("MEDIA", media.id(), TaskType.TRANSCODE))
                .toList();
        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "COMIC", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            markTranscodeQueued(item.getTargetId());
            enqueue(TaskType.TRANSCODE, item, "MEDIA", item.getTargetId());
        }
        log.info("转码命令已提交: comicId={}, taskId={}, items={}",
                comicId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), items.size());
    }

    public OperationSubmitResultDTO requestTranscodeForChapter(Long chapterId) {
        MediaCommandPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        List<MediaCommandPersistencePort.MediaSnapshot> toTranscode = persistencePort.findVideos(chapterId);
        List<MediaCommandPersistencePort.MediaSnapshot> eligible = toTranscode.stream()
                .filter(this::isTranscodeEligible)
                .toList();
        if (eligible.isEmpty()) {
            log.info("章节 {} 无待转码视频，跳过", chapterId);
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        List<CreateManagementTaskRequest.TaskTarget> targets = eligible.stream()
                .map(media -> target("MEDIA", media.id(), TaskType.TRANSCODE))
                .toList();
        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "CHAPTER", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            markTranscodeQueued(item.getTargetId());
            enqueue(TaskType.TRANSCODE, item, "MEDIA", item.getTargetId());
        }
        log.info("转码命令已提交: chapterId={}, taskId={}, items={}",
                chapterId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), items.size());
    }

    public OperationSubmitResultDTO requestTranscodeForMedia(Long mediaId) {
        MediaCommandPersistencePort.MediaSnapshot media = persistencePort.findMedia(mediaId);
        if (media == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "媒体页不存在: " + mediaId);
        }
        if (!isTranscodeEligible(media)) {
            log.info("媒体页 {} 无需转码，跳过", mediaId);
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "MEDIA",
                List.of(target("MEDIA", mediaId, TaskType.TRANSCODE)));
        ManagementTaskItemResponse item = managementTaskService.getTaskItems(task.getId()).get(0);

        markTranscodeQueued(mediaId);
        enqueue(TaskType.TRANSCODE, item, "MEDIA", mediaId);
        log.info("转码命令已提交: mediaId={}, taskId={}", mediaId, task.getId());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), 1);
    }

    private boolean isTranscodeEligible(MediaCommandPersistencePort.MediaSnapshot media) {
        if (!"VIDEO".equals(media.mediaType())) {
            return false;
        }
        if (media.hqStatus() == HqStatus.DELETED) {
            return false;
        }
        // 超高清视频（任一边 > 4096，如 8K）硬件编码器无法处理，CPU 转码又超时，
        // 判定为不可转码：保持原样并标记 NOT_NEEDED，避免反复进入转码队列失败
        if (!VideoPlayability.isTranscodable(media.width(), media.height())) {
            persistencePort.markTranscodeNotNeeded(media.id());
            log.info("视频分辨率超出硬件转码能力，标记无需转码: mediaId={}, {}x{}",
                    media.id(), media.width(), media.height());
            return false;
        }
        TranscodeStatus status = media.transcodeStatus();
        // safeValueOf 对未知枚举值返回 null，此处判空避免 Set12.contains(null) 抛 NPE
        if (status != null && (ACTIVE_TRANSCODE.contains(status) || status == TranscodeStatus.READY)) {
            return false;
        }
        // 判定基于视频编码 + 容器：mp4 容器 + mpeg4(MPEG-4 Part 2) 等老编码浏览器无法解码
        // （只出声不出画），必须转码为 H.264。判定收敛到共享模块避免 API/Worker 漂移。
        return !VideoPlayability.isBrowserPlayable(media.videoCodec(), media.container());
    }

    private void markTranscodeQueued(Long mediaId) {
        persistencePort.markTranscodeQueued(mediaId);
    }

    // ======================== 元数据刷新 ========================

    /**
     * 请求元数据扫盘刷新：重读 HQ 目录生成快照 → Worker 合并 DB（异步执行）。
     * <p>
     * 与 LQ/HQ/转码同一命令管线：同事务 CAS 漫画 READY→REFRESHING（0 行 = 并发被占用 409）、
     * 按章节创建 CHAPTER/METADATA_REFRESH item；零章节漫画保留单个 COMIC 空扫描项。
     * 漫画不存在 404；非 READY 状态 409。
     */
    @Transactional
    public OperationSubmitResultDTO requestMetadataRefresh(Long comicId) {
        MediaCommandPersistencePort.ComicSnapshot comic = persistencePort.findComic(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        if (comic.status() != ComicStatus.READY) {
            throw new ConflictException("漫画状态 " + comic.status() + " 不支持元数据刷新，仅 READY 可刷新");
        }

        List<MediaCommandPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId);
        List<CreateManagementTaskRequest.TaskTarget> targets = chapters.stream()
                .map(chapter -> target("CHAPTER", chapter.id(), TaskType.METADATA_REFRESH))
                .toList();
        if (targets.isEmpty()) {
            targets = List.of(target("COMIC", comicId, TaskType.METADATA_REFRESH));
        }
        ManagementTaskResponse task = createTask(TaskType.METADATA_REFRESH, "刷新元数据", "COMIC", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());
        for (ManagementTaskItemResponse item : items) {
            enqueue(TaskType.METADATA_REFRESH, item, item.getTargetType(), item.getTargetId());
        }
        log.info("元数据刷新命令已提交: comicId={}, taskId={}, chapters={}",
                comicId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.METADATA_REFRESH.name(),
                task.getStatus().name(), items.size());
    }

    // ======================== 整本删除（回收/永久清理重定向） ========================

    public OperationSubmitResultDTO requestComicDelete(Long comicId) {
        return trashLifecycleService.trashComic(comicId, null);
    }

    // ======================== 通用 ========================

    private ManagementTaskResponse createTask(TaskType operation, String operationLabel,
                                              String targetType,
                                              List<CreateManagementTaskRequest.TaskTarget> targets) {
        CreateManagementTaskRequest taskRequest = new CreateManagementTaskRequest();
        taskRequest.setTaskType(operation);
        taskRequest.setOperation(operationLabel);
        taskRequest.setTargetType(targetType);
        taskRequest.setTargets(targets);
        return managementTaskService.createTask(taskRequest, null, null);
    }

    private void enqueue(TaskType operation, ManagementTaskItemResponse item,
                         String targetType, Long targetId) {
        ManagementCommandRequestedEvent event = new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                operation.name(), targetType, targetId);
        outboxService.enqueue(event, EXCHANGE, ROUTING_REQUEST,
                item.getTaskId(), item.getId(), item.getAttempt());
    }

    private static CreateManagementTaskRequest.TaskTarget target(String targetType, Long targetId, TaskType operation) {
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(targetType);
        target.setTargetId(targetId);
        target.setOperationType(operation);
        return target;
    }
}
