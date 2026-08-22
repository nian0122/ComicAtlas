package com.comicatlas.api.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.payload.LqSizeResult;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 存储操作完成落库服务（LQ 生成 / HQ 删除 / 视频转码）。
 * <p>
 * 由 {@link ManagementCommandResultHandler} 在结果事件事务内调用：
 * 依据 Worker 回传的 completed / failed / progress 事件更新 media 行状态，
 * 并在完成时触发 {@link ComicStatsService} 重算整本统计。
 * <p>
 * LQ 完成时依据 {@link LqSizeResult}（Worker 回传的每页 LQ 产物大小）写入
 * media.lq_size，补全 lqSize 统计的数据来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaOperationCompletionService {

    /** 媒体类型：图片（LQ/HQ 删除仅作用于 IMAGE 页，VIDEO 不受影响）。 */
    private static final String MEDIA_TYPE_IMAGE = "IMAGE";

    /** 转码产物默认容器（事件未携带实测值时回退）。 */
    private static final String DEFAULT_CONTAINER = "mp4";

    /** 转码产物默认视频编码（事件未携带实测值时回退）。 */
    private static final String DEFAULT_VIDEO_CODEC = "h264";

    /** 转码产物默认音频编码（事件未携带实测值时回退）。 */
    private static final String DEFAULT_AUDIO_CODEC = "aac";

    /** LQ 缩略图扩展名。 */
    private static final String LQ_EXTENSION = ".webp";

    /** 转码产物扩展名。 */
    private static final String MP4_EXTENSION = ".mp4";

    private final MediaMapper mediaMapper;
    private final ManagementTaskService managementTaskService;
    private final MediaMetadataSyncService mediaMetadataSyncService;
    private final ComicStatsService comicStatsService;

    // ======================== LQ 生成 Completed ========================

    /**
     * LQ 生成完成：仅 Worker 回传有效产物大小的 IMAGE 页置 READY；
     * 未回传的跳过页置 NOT_GENERATED 并清空 LQ 引用，禁止数据库声称存在实际不存在的文件。
     * 完成后重算整本统计（lqSize/hqSize/totalPages/pageCount）。
     */
    public void applyLqCompleted(Long chapterId, List<LqSizeResult> lqSizes) {
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, MEDIA_TYPE_IMAGE));
        Map<Long, Long> sizeByMediaId = lqSizes == null ? Map.of() : lqSizes.stream()
                .filter(size -> size.mediaId() != null && size.sizeBytes() != null && size.sizeBytes() > 0)
                .collect(Collectors.toMap(LqSizeResult::mediaId, LqSizeResult::sizeBytes, (a, b) -> a));
        int readyPages = 0;
        for (Media media : mediaItems) {
            Long lqSize = sizeByMediaId.get(media.getId());
            if (lqSize != null) {
                LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                        .eq(Media::getId, media.getId())
                        .set(Media::getLqStatus, LqStatus.READY)
                        .set(Media::getLqRoot, StorageRootKeys.LQ)
                        .set(Media::getLqSize, lqSize);
                String hqPath = media.getHqPath();
                if (hqPath != null && !hqPath.isBlank()) {
                    mediaUpdate.set(Media::getLqPath, deriveLqPath(hqPath));
                }
                mediaMapper.update(null, mediaUpdate);
                readyPages++;
            } else {
                mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                        .eq(Media::getId, media.getId())
                        .set(Media::getLqStatus, LqStatus.NOT_GENERATED)
                        .set(Media::getLqRoot, null)
                        .set(Media::getLqPath, null)
                        .set(Media::getLqSize, 0L));
            }
        }
        comicStatsService.refreshByChapter(chapterId);
        log.info("LQ 完成业务更新: chapterId={}, readyPages={}, notGeneratedPages={}",
                chapterId, readyPages, mediaItems.size() - readyPages);
    }

    // ======================== HQ 删除 Completed ========================

    /**
     * HQ 删除完成：IMAGE 页批量置 DELETED 并清空 HQ 引用，
     * 完成后重算整本 hqSize（非 DELETED 行的 fileSize 之和）。
     */
    public void applyHqDeleteCompleted(Long chapterId) {
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .eq(Media::getChapterId, chapterId)
                        .eq(Media::getMediaType, MEDIA_TYPE_IMAGE)
                        .in(Media::getHqStatus, HqStatus.READY, HqStatus.DELETE_QUEUED, HqStatus.DELETING, HqStatus.MISSING));
        for (Media media : mediaItems) {
            mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, media.getId())
                    .set(Media::getHqStatus, HqStatus.DELETED)
                    .set(Media::getHqRoot, null)
                    .set(Media::getHqPath, null));
        }
        comicStatsService.refreshByChapter(chapterId);
        log.info("HQ 删除完成业务更新: chapterId={}, pages={}", chapterId, mediaItems.size());
    }

    // ======================== 视频转码 Completed ========================

    /**
     * 转码完成业务更新：实测元数据（duration/fileSize/真实 codec）优先，
     * 事件未携带时回退旧的硬编码 mp4/h264/aac；hq_path 以事件实测
     * {@code transcode.newHqPath()} 为准（含防撞名），老消息回退
     * {@code deriveTranscodedPath}。
     */
    public void applyTranscodeCompleted(ManagementCommandCompletedEvent ev, Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null) {
            return;
        }
        TranscodeMediaInfo transcode = ev.transcode();
        String hqPath = media.getHqPath();
        LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .set(Media::getTranscodeStatus, TranscodeStatus.READY)
                .set(Media::getContainer, transcode != null && transcode.container() != null
                        ? transcode.container() : DEFAULT_CONTAINER)
                .set(Media::getVideoCodec, transcode != null && transcode.videoCodec() != null
                        ? transcode.videoCodec() : DEFAULT_VIDEO_CODEC)
                .set(Media::getAudioCodec, transcode != null && transcode.audioCodec() != null
                        ? transcode.audioCodec() : DEFAULT_AUDIO_CODEC);
        if (transcode != null) {
            if (transcode.duration() != null) {
                mediaUpdate.set(Media::getDuration, transcode.duration());
            }
            if (transcode.fileSize() != null) {
                mediaUpdate.set(Media::getHqSize, transcode.fileSize());
            }
        }
        if (hqPath != null && !hqPath.isBlank()) {
            // 优先使用 Worker 实测写入路径（含防撞名 {base}.transcoded-{mediaId}.mp4 场景）；
            // 老消息无 newHqPath 时回退 deriveTranscodedPath（{base}.mp4）
            String newHqPath = transcode != null && transcode.newHqPath() != null
                    && !transcode.newHqPath().isBlank()
                    ? transcode.newHqPath()
                    : deriveTranscodedPath(hqPath);
            mediaUpdate.set(Media::getHqPath, newHqPath);
        }
        mediaMapper.update(null, mediaUpdate);
        log.info("转码完成业务更新: mediaId={}", mediaId);
    }

    /**
     * 转码任务全部完成（无剩余未完成项）时，触发一次整本元数据同步
     * （聚合统计 + 重导出 metadata.json），避免每个视频各自聚合与重导出。
     */
    public void maybeNotifyTranscodeTaskCompleted(ManagementCommandCompletedEvent ev) {
        if (managementTaskService.countActiveItems(ev.taskId()) > 0) {
            log.debug("转码任务仍有未完成项，跳过元数据同步: taskId={}", ev.taskId());
            return;
        }
        if ("COMIC".equals(ev.targetType())) {
            mediaMetadataSyncService.notifyTaskTranscoded(ev.targetId(), ev.taskId());
        } else {
            mediaMetadataSyncService.notifyTranscoded(ev.targetId(), ev.taskId());
        }
    }

    // ======================== Failed 回退 ========================

    /** LQ 生成失败：QUEUED/GENERATING → FAILED。 */
    public void revertLqFailed(Long chapterId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, MEDIA_TYPE_IMAGE)
                .in(Media::getLqStatus, LqStatus.QUEUED, LqStatus.GENERATING)
                .set(Media::getLqStatus, LqStatus.FAILED));
    }

    /** HQ 删除失败：DELETE_QUEUED/DELETING → FAILED。 */
    public void revertHqDeleteFailed(Long targetId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, targetId)
                .in(Media::getHqStatus, HqStatus.DELETE_QUEUED, HqStatus.DELETING)
                .set(Media::getHqStatus, HqStatus.FAILED));
    }

    /** 转码失败：QUEUED/TRANSCODING → FAILED。 */
    public void revertTranscodeFailed(Long mediaId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .in(Media::getTranscodeStatus, TranscodeStatus.QUEUED, TranscodeStatus.TRANSCODING)
                .set(Media::getTranscodeStatus, TranscodeStatus.FAILED));
    }

    // ======================== Progress 状态转换 ========================

    /** LQ 生成开始：QUEUED → GENERATING。 */
    public void transitionLqGenerating(Long chapterId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getLqStatus, LqStatus.QUEUED)
                .set(Media::getLqStatus, LqStatus.GENERATING));
    }

    /** HQ 删除开始：DELETE_QUEUED → DELETING。 */
    public void transitionHqDeleting(Long chapterId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getHqStatus, HqStatus.DELETE_QUEUED)
                .set(Media::getHqStatus, HqStatus.DELETING));
    }

    /** 转码开始：QUEUED → TRANSCODING。 */
    public void transitionTranscoding(Long mediaId) {
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .eq(Media::getTranscodeStatus, TranscodeStatus.QUEUED)
                .set(Media::getTranscodeStatus, TranscodeStatus.TRANSCODING));
    }

    // ======================== 辅助 ========================

    private static String deriveLqPath(String hqPath) {
        return hqPath.replaceAll("\\.[^.]+$", LQ_EXTENSION);
    }

    private static String deriveTranscodedPath(String hqPath) {
        int lastSlash = hqPath.lastIndexOf('/');
        String dir = lastSlash > 0 ? hqPath.substring(0, lastSlash + 1) : "";
        String name = hqPath.substring(lastSlash + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return dir + base + MP4_EXTENSION;
    }
}
