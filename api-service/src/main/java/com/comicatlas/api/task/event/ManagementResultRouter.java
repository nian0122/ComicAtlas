package com.comicatlas.api.task.event;

import com.comicatlas.api.media.service.MediaOperationCompletionService;
import com.comicatlas.api.metadata.service.MetadataRefreshCompletionService;
import com.comicatlas.api.metadata.service.MetadataUpdateCoordinator;
import com.comicatlas.api.recovery.trash.TrashLifecycleCompletionService;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.upload.service.UploadCompletionService;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 管理命令结果的领域路由器，不负责 MQ ACK、幂等或事务边界。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementResultRouter {

    private static final Set<String> LQ_OPERATIONS = Set.of("LQ_GENERATE", "LQ_REGENERATE");
    private static final String COMIC_TARGET = "COMIC";
    private static final String METADATA_REFRESH = "METADATA_REFRESH";

    private final MediaOperationCompletionService mediaCompletionService;
    private final TrashLifecycleCompletionService trashCompletionService;
    private final UploadCompletionService uploadCompletionService;
    private final MetadataRefreshCompletionService metadataCompletionService;
    private final ComicStatsService comicStatsService;
    private final MetadataUpdateCoordinator metadataUpdateCoordinator;

    /** 路由成功结果并触发元数据同步。 */
    public void routeCompleted(ManagementCommandCompletedEvent event) {
        boolean comicScope = COMIC_TARGET.equals(event.targetType());
        switch (event.operationType()) {
            case "LQ_GENERATE", "LQ_REGENERATE" -> forChapters(event, comicScope,
                    chapterId -> mediaCompletionService.applyLqCompleted(chapterId, event.lqSizes()));
            case "HQ_DELETE" -> forChapters(event, comicScope,
                    chapterId -> mediaCompletionService.applyHqDeleteCompleted(chapterId));
            case "TRANSCODE" -> {
                if (comicScope) {
                    comicStatsService.mediaIdsOf(event.targetId())
                            .forEach(mediaId -> mediaCompletionService.applyTranscodeCompleted(event, mediaId));
                } else {
                    mediaCompletionService.applyTranscodeCompleted(event, event.targetId());
                }
                mediaCompletionService.maybeNotifyTranscodeTaskCompleted(event);
            }
            case "COMIC_DELETE" -> trashCompletionService.applyComicTrashCompleted(event.targetId());
            case "CHAPTER_TRASH" -> trashCompletionService.applyChapterTrashCompleted(event.targetId());
            case "MEDIA_TRASH" -> trashCompletionService.applyMediaTrashCompleted(event);
            case "COMIC_RESTORE" -> trashCompletionService.applyComicRestoreCompleted(event.targetId());
            case "CHAPTER_RESTORE" -> trashCompletionService.applyChapterRestoreCompleted(event.targetId());
            case "MEDIA_RESTORE" -> trashCompletionService.applyMediaRestoreCompleted(event.targetId());
            case "COMIC_PURGE" -> trashCompletionService.applyComicPurgeCompleted(event.targetId());
            case "CHAPTER_PURGE" -> trashCompletionService.applyChapterPurgeCompleted(event.targetId());
            case "MEDIA_PURGE" -> trashCompletionService.applyMediaPurgeCompleted(event.targetId());
            case METADATA_REFRESH -> log.warn("通用 completed 事件携带元数据刷新: comicId={}", event.targetId());
            default -> log.warn("未知 completed 操作类型: {}", event.operationType());
        }
        if (!METADATA_REFRESH.equals(event.operationType())) {
            metadataUpdateCoordinator.requestSyncForTarget(event.targetType(), event.targetId(),
                    event.taskId(), "命令完成: " + event.operationType());
        }
    }

    /** 路由失败结果并执行领域回退。 */
    public void routeFailed(ManagementCommandFailedEvent event) {
        switch (event.operationType()) {
            case "LQ_GENERATE", "LQ_REGENERATE" -> mediaCompletionService.revertLqFailed(event.targetId());
            case "HQ_DELETE" -> mediaCompletionService.revertHqDeleteFailed(event.targetId());
            case "TRANSCODE" -> mediaCompletionService.revertTranscodeFailed(event.targetId());
            case "MEDIA_UPLOAD", "MEDIA_REPLACE" -> uploadCompletionService.revertUploadFailed(event.targetId());
            case METADATA_REFRESH -> metadataCompletionService.releaseComicRefreshing(event.targetId());
            case "COMIC_DELETE", "CHAPTER_TRASH", "MEDIA_TRASH" ->
                    trashCompletionService.applyTrashFailed(event.targetType(), event.targetId(), event.taskId());
            case "COMIC_RESTORE", "CHAPTER_RESTORE", "MEDIA_RESTORE", "COMIC_PURGE", "CHAPTER_PURGE", "MEDIA_PURGE" ->
                    trashCompletionService.revertToTrashed(event.targetType(), event.targetId());
            default -> { }
        }
        log.info("命令失败业务回退: itemId={}, op={}, target={}", event.itemId(), event.operationType(), event.targetId());
    }

    /** 路由进度结果并推进媒体状态。 */
    public void routeProgress(ManagementCommandProgressEvent event) {
        if (LQ_OPERATIONS.contains(event.operationType())) {
            mediaCompletionService.transitionLqGenerating(event.targetId());
        } else if ("HQ_DELETE".equals(event.operationType())) {
            mediaCompletionService.transitionHqDeleting(event.targetId());
        } else if ("TRANSCODE".equals(event.operationType())) {
            mediaCompletionService.transitionTranscoding(event.targetId());
        }
    }

    /** 路由媒体上传/替换完成结果。 */
    public void routeUploadCompleted(MediaUploadCompletedEvent event) {
        uploadCompletionService.applyUploadCompletedBusiness(event);
        metadataUpdateCoordinator.requestSyncForTarget(event.targetType(), event.targetId(),
                event.taskId(), "上传完成: " + event.operationType());
    }

    private void forChapters(ManagementCommandCompletedEvent event, boolean comicScope,
                             java.util.function.LongConsumer action) {
        if (comicScope) {
            comicStatsService.chapterIdsOf(event.targetId()).forEach(chapterId -> action.accept(chapterId));
        } else {
            action.accept(event.targetId());
        }
    }
}
