package com.comicatlas.api.management.operation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.policy.TranscodeEligibility;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
public class MediaOperationCommandService {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicMapper comicMapper;
    private final ManagementTaskService managementTaskService;
    private final OutboxService outboxService;
    private final TrashLifecycleService trashLifecycleService;
    private final TranscodeMediaSelector transcodeMediaSelector;

    private static final String EXCHANGE = MqExchanges.MANAGEMENT;
    private static final String ROUTING_REQUEST = MqRoutingKeys.COMMAND_REQUESTED;

    // ======================== LQ 生成 ========================

    public OperationSubmitResultDTO requestLqForComic(Long comicId, boolean regenerate) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        TaskType operation = regenerate ? TaskType.LQ_REGENERATE : TaskType.LQ_GENERATE;

        List<CreateManagementTaskRequest.TaskTarget> targets = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<Media> eligible = eligibleLqPages(chapter.getId(), regenerate);
            if (!eligible.isEmpty()) {
                targets.add(target("CHAPTER", chapter.getId(), operation));
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
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        TaskType operation = regenerate ? TaskType.LQ_REGENERATE : TaskType.LQ_GENERATE;

        List<Media> eligible = eligibleLqPages(chapterId, regenerate);
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

    private List<Media> eligibleLqPages(Long chapterId, boolean regenerate) {
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "IMAGE"));
        return mediaItems.stream()
                .filter(media -> media.getHqStatus() != HqStatus.DELETED)
                .filter(media -> regenerate || media.getLqStatus() != LqStatus.READY)
                .toList();
    }

    private void markLqQueued(Long chapterId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE")
                .ne(Media::getHqStatus, HqStatus.DELETED)
                .set(Media::getLqStatus, LqStatus.QUEUED));
    }

    // ======================== HQ 删除 ========================

    public OperationSubmitResultDTO requestHqDeleteForComic(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));

        List<CreateManagementTaskRequest.TaskTarget> targets = new ArrayList<>();
        for (Chapter chapter : chapters) {
            if (!hasDeletableHq(chapter.getId())) {
                continue;
            }
            validateHqDeletePrecondition(chapter.getId());
            targets.add(target("CHAPTER", chapter.getId(), TaskType.HQ_DELETE));
        }
        if (targets.isEmpty()) {
            log.info("漫画 {} 无可删除 HQ 的章节，跳过", comicId);
            return OperationSubmitResultDTO.of(null, TaskType.HQ_DELETE.name(), null, 0);
        }

        ManagementTaskResponse task = createTask(TaskType.HQ_DELETE, "删除高清图片", "COMIC", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            markHqDeleteQueued(item.getTargetId());
            enqueue(TaskType.HQ_DELETE, item, "CHAPTER", item.getTargetId());
        }
        log.info("HQ 删除命令已提交: comicId={}, taskId={}, items={}",
                comicId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.HQ_DELETE.name(), task.getStatus().name(), items.size());
    }

    public OperationSubmitResultDTO requestHqDeleteForChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
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
        return mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE")
                .in(Media::getHqStatus, HqStatus.READY, HqStatus.MISSING)) > 0;
    }

    /**
     * HQ 删除前置条件：全部图片页 LQ 必须 READY。
     */
    private void validateHqDeletePrecondition(Long chapterId) {
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, "IMAGE")
                        .in(Media::getHqStatus, HqStatus.READY, HqStatus.MISSING));
        List<Media> notReady = mediaItems.stream()
                .filter(media -> media.getLqStatus() != LqStatus.READY)
                .toList();
        if (!notReady.isEmpty()) {
            List<String> details = notReady.stream()
                    .map(media -> String.format("第 %d 页 (pageId=%d, lqStatus=%s)",
                            media.getPageNumber(), media.getId(), media.getLqStatus()))
                    .toList();
            throw new ConflictException("HQ 删除前置条件不满足：以下页面 LQ 未就绪 -> " + details);
        }
    }

    private void markHqDeleteQueued(Long chapterId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE")
                .in(Media::getHqStatus, HqStatus.READY, HqStatus.MISSING)
                .set(Media::getHqStatus, HqStatus.DELETE_QUEUED));
    }

    // ======================== 视频转码 ========================

    /**
     * 请求整本视频转码：漫画入口必须预取并展开为每个视频一个 MEDIA item，
     * Worker 只接收 MEDIA 目标（不接收聚合 COMIC 转码 item）。
     * <p>
     * 资格：{@link TranscodeEligibility#isEligible}（VIDEO + HQ 可用 + 生命周期可操作
     * + transcodeStatus ∈ {REQUIRED, FAILED}）。并发冲突由 {@code markTranscodeQueued}
     * 的 CAS 语义保证：影响 0 行 → 409，与 createTask 同事务整体回滚，不产生孤儿任务。
     */
    @Transactional
    public OperationSubmitResultDTO requestTranscodeForComic(Long comicId) {
        List<Media> eligible = transcodeMediaSelector.eligibleVideosOfComics(List.of(comicId));
        if (eligible.isEmpty()) {
            log.info("漫画 {} 无待转码视频，跳过", comicId);
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        List<CreateManagementTaskRequest.TaskTarget> targets = eligible.stream()
                .map(media -> target("MEDIA", media.getId(), TaskType.TRANSCODE))
                .toList();
        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "COMIC", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            transcodeMediaSelector.markTranscodeQueued(item.getTargetId());
            enqueue(TaskType.TRANSCODE, item, "MEDIA", item.getTargetId());
        }
        log.info("转码命令已提交: comicId={}, taskId={}, items={}",
                comicId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), items.size());
    }

    @Transactional
    public OperationSubmitResultDTO requestTranscodeForChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        List<Media> eligible = transcodeMediaSelector.eligibleVideosOfChapter(chapterId);
        if (eligible.isEmpty()) {
            log.info("章节 {} 无待转码视频，跳过", chapterId);
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        List<CreateManagementTaskRequest.TaskTarget> targets = eligible.stream()
                .map(media -> target("MEDIA", media.getId(), TaskType.TRANSCODE))
                .toList();
        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "CHAPTER", targets);
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());

        for (ManagementTaskItemResponse item : items) {
            transcodeMediaSelector.markTranscodeQueued(item.getTargetId());
            enqueue(TaskType.TRANSCODE, item, "MEDIA", item.getTargetId());
        }
        log.info("转码命令已提交: chapterId={}, taskId={}, items={}",
                chapterId, task.getId(), items.size());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), items.size());
    }

    @Transactional
    public OperationSubmitResultDTO requestTranscodeForMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "媒体页不存在: " + mediaId);
        }
        if (!TranscodeEligibility.isEligible(media)) {
            log.info("媒体页 {} 无需转码，跳过", mediaId);
            return OperationSubmitResultDTO.of(null, TaskType.TRANSCODE.name(), null, 0);
        }

        ManagementTaskResponse task = createTask(TaskType.TRANSCODE, "视频转码", "MEDIA",
                List.of(target("MEDIA", mediaId, TaskType.TRANSCODE)));
        ManagementTaskItemResponse item = managementTaskService.getTaskItems(task.getId()).get(0);

        transcodeMediaSelector.markTranscodeQueued(mediaId);
        enqueue(TaskType.TRANSCODE, item, "MEDIA", mediaId);
        log.info("转码命令已提交: mediaId={}, taskId={}", mediaId, task.getId());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.TRANSCODE.name(), task.getStatus().name(), 1);
    }

    // ======================== 元数据刷新 ========================

    /**
     * 请求元数据扫盘刷新：重读 HQ 目录生成快照 → Worker 合并 DB（异步执行）。
     * <p>
     * 与 LQ/HQ/转码同一命令管线：同事务 CAS 漫画 READY→REFRESHING（0 行 = 并发被占用 409）、
     * 创建单个 COMIC/METADATA_REFRESH item（零章节也创建），并发布命令到 Outbox。
     * 漫画不存在 404；非 READY 状态 409。
     */
    @Transactional
    public OperationSubmitResultDTO requestMetadataRefresh(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        if (comic.getStatus() != ComicStatus.READY) {
            throw new ConflictException("漫画状态 " + comic.getStatus() + " 不支持元数据刷新，仅 READY 可刷新");
        }

        ManagementTaskResponse task = createTask(TaskType.METADATA_REFRESH, "刷新元数据", "COMIC",
                List.of(target("COMIC", comicId, TaskType.METADATA_REFRESH)));
        ManagementTaskItemResponse item = managementTaskService.getTaskItems(task.getId()).get(0);

        enqueue(TaskType.METADATA_REFRESH, item, "COMIC", comicId);
        log.info("元数据刷新命令已提交: comicId={}, taskId={}", comicId, task.getId());
        return OperationSubmitResultDTO.of(task.getId(), TaskType.METADATA_REFRESH.name(),
                task.getStatus().name(), 1);
    }

    // ======================== 整本删除（回收/永久清理重定向） ========================

    public OperationSubmitResultDTO requestComicDelete(Long comicId) {
        return trashLifecycleService.trashComic(comicId, null);
    }

    // ======================== 通用 ========================

    private ManagementTaskResponse createTask(TaskType operation, String operationLabel,
                                              String targetType,
                                              List<CreateManagementTaskRequest.TaskTarget> targets) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(operation);
        req.setOperation(operationLabel);
        req.setTargetType(targetType);
        req.setTargets(targets);
        return managementTaskService.createTask(req, null, null);
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
