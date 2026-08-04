package com.comicatlas.api.management.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体操作资格服务 — 依据真实 DB 资产状态返回可查询的 allowedOperations。
 * <p>
 * 前端按钮所需状态全部由此服务计算，不自行复制操作矩阵。
 */
@Service
@RequiredArgsConstructor
public class MediaOperationEligibilityService {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final OperationPolicyService policyService;

    private static final Set<String> COMPAT_CONTAINERS = Set.of("mp4", "webm");

    public AllowedOperations forComic(Long comicId) {
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        boolean anyLqWork = false;
        boolean anyLqReady = false;
        boolean anyHqWork = false;
        boolean hqPreconditionBlocked = false;
        boolean anyTranscode = false;

        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        for (Chapter ch : chapters) {
            ChapterOps ops = collectChapterAssetOps(ch.getId());
            anyLqWork |= ops.lqGenerateAllowed;
            anyLqReady |= ops.lqRegenerateAllowed;
            anyHqWork |= ops.hqDeleteAllowed;
            hqPreconditionBlocked |= ops.hqDeleteBlocked;
            anyTranscode |= ops.transcodeAllowed;
        }

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
        allowed.add(OperationPolicyService.OP_METADATA_REFRESH);

        return AllowedOperations.of(allowed, blocked);
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
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return AllowedOperations.none("媒体页不存在");
        }
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        if ("VIDEO".equals(media.getMediaType())
                && !"DELETED".equals(media.getHqStatus())
                && !"READY".equals(media.getTranscodeStatus())
                && !"QUEUED".equals(media.getTranscodeStatus())
                && !"TRANSCODING".equals(media.getTranscodeStatus())
                && (media.getContainer() == null
                    || !COMPAT_CONTAINERS.contains(media.getContainer().toLowerCase()))) {
            allowed.add(OperationPolicyService.OP_TRANSCODE);
        } else {
            blocked.put(OperationPolicyService.OP_TRANSCODE, "该媒体无需转码或处于转码中");
        }
        return AllowedOperations.of(allowed, blocked);
    }

    private ChapterOps collectChapterAssetOps(Long chapterId) {
        List<Media> pages = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
        List<Media> imagePages = pages.stream()
                .filter(p -> "IMAGE".equals(p.getMediaType()))
                .toList();
        List<Media> deletableHq = imagePages.stream()
                .filter(p -> "READY".equals(p.getHqStatus()) || "MISSING".equals(p.getHqStatus()))
                .toList();

        ChapterOps ops = new ChapterOps();
        ops.lqGenerateAllowed = imagePages.stream()
                .anyMatch(p -> !"DELETED".equals(p.getHqStatus()) && !"READY".equals(p.getLqStatus()));
        ops.lqRegenerateAllowed = imagePages.stream()
                .anyMatch(p -> !"DELETED".equals(p.getHqStatus()));
        ops.hqDeleteBlocked = deletableHq.stream().anyMatch(p -> !"READY".equals(p.getLqStatus()));
        ops.hqDeleteAllowed = !deletableHq.isEmpty() && !ops.hqDeleteBlocked;
        ops.transcodeAllowed = pages.stream().anyMatch(p ->
                "VIDEO".equals(p.getMediaType())
                        && !"DELETED".equals(p.getHqStatus())
                        && !"READY".equals(p.getTranscodeStatus())
                        && !"QUEUED".equals(p.getTranscodeStatus())
                        && !"TRANSCODING".equals(p.getTranscodeStatus())
                        && (p.getContainer() == null
                            || !COMPAT_CONTAINERS.contains(p.getContainer().toLowerCase())));
        return ops;
    }

    private static final class ChapterOps {
        boolean lqGenerateAllowed;
        boolean lqRegenerateAllowed;
        boolean hqDeleteAllowed;
        boolean hqDeleteBlocked;
        boolean transcodeAllowed;
    }
}
