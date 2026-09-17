package com.comicatlas.api.task.application.service.impl;

import com.comicatlas.api.task.domain.policy.AllowedOperations;
import com.comicatlas.api.task.domain.policy.OperationPolicyService;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.api.task.application.port.in.MediaOperationEligibilityService;
import com.comicatlas.api.task.application.port.out.MediaOperationEligibilityPersistencePort;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.common.media.video.VideoPlayability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 媒体操作资格服务 — 依据真实 DB 资产状态返回可查询的 allowedOperations。
 * <p>
 * 前端按钮所需状态全部由此服务计算，不自行复制操作矩阵。
 */
@Service
@RequiredArgsConstructor
public class MediaOperationEligibilityServiceImpl implements MediaOperationEligibilityService {

    private final MediaOperationEligibilityPersistencePort persistencePort;
    private final OperationPolicyService policyService;

    public AllowedOperations forComic(Long comicId) {
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();
        MediaOperationEligibilityPersistencePort.ComicSnapshot comic = persistencePort.findComic(comicId);
        if (comic == null) {
            return AllowedOperations.none("漫画不存在");
        }

        // 生命周期权限与媒体资产权限共用一个接口，避免操作台漏掉回收/恢复/永久清理入口。
        AllowedOperations lifecycleOperations = policyService.forComic(comic.status().name());
        mergeLifecycleOperation(lifecycleOperations, OperationPolicyService.OP_DELETE, allowed, blocked);
        mergeLifecycleOperation(lifecycleOperations, OperationPolicyService.OP_RECOVER, allowed, blocked);
        mergeLifecycleOperation(lifecycleOperations, OperationPolicyService.OP_PURGE, allowed, blocked);

        boolean anyLqWork = false;
        boolean anyLqReady = false;
        boolean anyHqWork = false;
        boolean hqPreconditionBlocked = false;
        boolean anyTranscode = false;

        List<MediaOperationEligibilityPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId);
        if (chapters.isEmpty()) {
            return buildComicOperations(comic, allowed, blocked, false, false, false, false, false);
        }

        List<Long> chapterIds = chapters.stream().map(MediaOperationEligibilityPersistencePort.ChapterSnapshot::id).toList();
        Map<Long, List<MediaOperationEligibilityPersistencePort.MediaSnapshot>> mediaByChapterId = persistencePort.findMediaByChapters(chapterIds)
                .stream()
                .collect(Collectors.groupingBy(MediaOperationEligibilityPersistencePort.MediaSnapshot::chapterId));
        for (Long chapterId : chapterIds) {
            ChapterOps ops = collectChapterAssetOps(mediaByChapterId.getOrDefault(chapterId, List.of()));
            anyLqWork |= ops.lqGenerateAllowed;
            anyLqReady |= ops.lqRegenerateAllowed;
            anyHqWork |= ops.hqDeleteAllowed;
            hqPreconditionBlocked |= ops.hqDeleteBlocked;
            anyTranscode |= ops.transcodeAllowed;
        }

        return buildComicOperations(comic, allowed, blocked, anyLqWork, anyLqReady, anyHqWork,
                hqPreconditionBlocked, anyTranscode);
    }

    private AllowedOperations buildComicOperations(MediaOperationEligibilityPersistencePort.ComicSnapshot comic, Set<String> allowed,
                                                    Map<String, String> blocked,
                                                    boolean anyLqWork, boolean anyLqReady,
                                                    boolean anyHqWork, boolean hqPreconditionBlocked,
                                                    boolean anyTranscode) {
        if (anyLqWork) {
            allowed.add(OperationPolicyService.OP_LQ_GENERATE);
        } else {
            blocked.put(OperationPolicyService.OP_LQ_GENERATE, "没有需要生成 LQ 的页面");
        }
        if (anyLqReady) {
            allowed.add(OperationPolicyService.OP_LQ_REGENERATE);
        } else {
            blocked.put(OperationPolicyService.OP_LQ_REGENERATE, "没有可重新生成 LQ 的页面");
        }
        if (anyHqWork) {
            allowed.add(OperationPolicyService.OP_HQ_DELETE);
        } else if (hqPreconditionBlocked) {
            blocked.put(OperationPolicyService.OP_HQ_DELETE, "存在 LQ 未就绪的图片页");
        } else {
            blocked.put(OperationPolicyService.OP_HQ_DELETE, "没有可删除 HQ 的图片页");
        }
        if (anyTranscode) {
            allowed.add(OperationPolicyService.OP_TRANSCODE);
        } else {
            blocked.put(OperationPolicyService.OP_TRANSCODE, "没有需要转码的视频页");
        }
        if (comic.status() == ComicStatus.READY) {
            allowed.add(OperationPolicyService.OP_METADATA_REFRESH);
        } else {
            blocked.put(OperationPolicyService.OP_METADATA_REFRESH,
                    "漫画状态不是 READY，无法刷新元数据");
        }

        return AllowedOperations.of(allowed, blocked);
    }

    private static void mergeLifecycleOperation(AllowedOperations lifecycleOperations, String operation,
                                                Set<String> allowed, Map<String, String> blocked) {
        if (lifecycleOperations.isAllowed(operation)) {
            allowed.add(operation);
            blocked.remove(operation);
        } else if (lifecycleOperations.blockedReasons().containsKey(operation)) {
            blocked.put(operation, lifecycleOperations.blockedReasons().get(operation));
        }
    }

    public AllowedOperations forChapter(Long chapterId) {
        ChapterOps ops = collectChapterAssetOps(chapterId);
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        if (ops.lqGenerateAllowed) {
            allowed.add(OperationPolicyService.OP_LQ_GENERATE);
        } else {
            blocked.put(OperationPolicyService.OP_LQ_GENERATE, "本章节没有需要生成 LQ 的页面");
        }
        if (ops.lqRegenerateAllowed) {
            allowed.add(OperationPolicyService.OP_LQ_REGENERATE);
        } else {
            blocked.put(OperationPolicyService.OP_LQ_REGENERATE, "本章节没有可重新生成 LQ 的页面");
        }
        if (ops.hqDeleteAllowed) {
            allowed.add(OperationPolicyService.OP_HQ_DELETE);
        } else if (ops.hqDeleteBlocked) {
            blocked.put(OperationPolicyService.OP_HQ_DELETE, "章节图片 LQ 未全部就绪，无法删除 HQ");
        } else {
            blocked.put(OperationPolicyService.OP_HQ_DELETE, "章节没有可删除的 HQ");
        }
        if (ops.transcodeAllowed) {
            allowed.add(OperationPolicyService.OP_TRANSCODE);
        } else {
            blocked.put(OperationPolicyService.OP_TRANSCODE, "章节没有需要转码的视频页");
        }
        return AllowedOperations.of(allowed, blocked);
    }

    public AllowedOperations forMedia(Long mediaId) {
        MediaOperationEligibilityPersistencePort.MediaSnapshot media = persistencePort.findMedia(mediaId);
        if (media == null) {
            return AllowedOperations.none("媒体页不存在");
        }
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        if ("VIDEO".equals(media.mediaType())
                && media.hqStatus() != HqStatus.DELETED
                && media.transcodeStatus() != TranscodeStatus.READY
                && media.transcodeStatus() != TranscodeStatus.QUEUED
                && media.transcodeStatus() != TranscodeStatus.TRANSCODING
                && VideoPlayability.isTranscodable(media.width(), media.height())
                && !VideoPlayability.isBrowserPlayable(media.videoCodec(), media.container())) {
            allowed.add(OperationPolicyService.OP_TRANSCODE);
        } else {
            blocked.put(OperationPolicyService.OP_TRANSCODE, "该媒体无需转码或处于转码中");
        }
        return AllowedOperations.of(allowed, blocked);
    }

    private ChapterOps collectChapterAssetOps(List<MediaOperationEligibilityPersistencePort.MediaSnapshot> mediaItems) {
        List<MediaOperationEligibilityPersistencePort.MediaSnapshot> imagePages = mediaItems.stream()
                .filter(p -> "IMAGE".equals(p.mediaType()))
                .toList();
        List<MediaOperationEligibilityPersistencePort.MediaSnapshot> deletableHq = imagePages.stream()
                .filter(p -> p.hqStatus() == HqStatus.READY || p.hqStatus() == HqStatus.MISSING)
                .toList();

        ChapterOps ops = new ChapterOps();
        ops.lqGenerateAllowed = imagePages.stream()
                .anyMatch(p -> p.hqStatus() != HqStatus.DELETED && p.lqStatus() != LqStatus.READY);
        ops.lqRegenerateAllowed = imagePages.stream()
                .anyMatch(p -> p.hqStatus() != HqStatus.DELETED);
        ops.hqDeleteBlocked = deletableHq.stream().anyMatch(p -> p.lqStatus() != LqStatus.READY);
        ops.hqDeleteAllowed = !deletableHq.isEmpty() && !ops.hqDeleteBlocked;
        ops.transcodeAllowed = mediaItems.stream().anyMatch(p ->
                "VIDEO".equals(p.mediaType())
                        && p.hqStatus() != HqStatus.DELETED
                        && p.transcodeStatus() != TranscodeStatus.READY
                        && p.transcodeStatus() != TranscodeStatus.QUEUED
                        && p.transcodeStatus() != TranscodeStatus.TRANSCODING
                        && VideoPlayability.isTranscodable(p.width(), p.height())
                        && !VideoPlayability.isBrowserPlayable(p.videoCodec(), p.container()));
        return ops;
    }

    private ChapterOps collectChapterAssetOps(Long chapterId) {
        List<MediaOperationEligibilityPersistencePort.MediaSnapshot> mediaItems = persistencePort.findMediaByChapter(chapterId);
        return collectChapterAssetOps(mediaItems);
    }

    private static final class ChapterOps {
        boolean lqGenerateAllowed;
        boolean lqRegenerateAllowed;
        boolean hqDeleteAllowed;
        boolean hqDeleteBlocked;
        boolean transcodeAllowed;
    }
}
