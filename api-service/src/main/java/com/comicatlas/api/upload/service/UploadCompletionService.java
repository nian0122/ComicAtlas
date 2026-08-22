package com.comicatlas.api.upload.service;
import com.comicatlas.api.upload.domain.UploadSessionStatus;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import com.comicatlas.api.upload.persistence.mapper.UploadSessionMapper;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 媒体上传/替换完成落库服务（MEDIA_UPLOAD / MEDIA_REPLACE）。
 * <p>
 * 由 {@link ManagementCommandResultHandler} 在结果事件事务内调用：
 * 依据 Worker 回传的每媒体分析结果将 STAGING 更新为 READY
 * （replace 保留 mediaId/pageNumber 并重置 LQ/transcode），
 * 完成后触发 {@link ComicStatsService} 重算整本统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCompletionService {

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final UploadSessionService uploadSessionService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ComicStatsService comicStatsService;

    /** 媒体上传/替换完成业务更新：分析结果逐媒体落库 + 统计重算 + 会话清理。 */
    public void applyUploadCompletedBusiness(MediaUploadCompletedEvent ev) {
        boolean replace = "MEDIA_REPLACE".equals(ev.operationType());
        for (MediaAnalysisResult result : ev.results()) {
            if (result.mediaId() == null) {
                continue;
            }
            LambdaUpdateWrapper<Media> mediaUpdate = new LambdaUpdateWrapper<Media>()
                    .eq(Media::getId, result.mediaId())
                    .set(Media::getStatus, MediaLifecycleStatus.READY)
                    .set(Media::getHqStatus, HqStatus.READY)
                    .set(Media::getWidth, result.width())
                    .set(Media::getHeight, result.height())
                    .set(Media::getHqSize, result.fileSize())
                    .set(Media::getMediaType, result.mediaType())
                    .set(Media::getDuration, result.duration())
                    .set(Media::getContainer, result.container())
                    .set(Media::getVideoCodec, result.videoCodec())
                    .set(Media::getAudioCodec, result.audioCodec());
            if (result.hqRoot() != null && !result.hqRoot().isBlank()) {
                mediaUpdate.set(Media::getHqRoot, result.hqRoot());
            }
            if (result.hqPath() != null && !result.hqPath().isBlank()) {
                mediaUpdate.set(Media::getHqPath, result.hqPath());
            }
            if (replace) {
                // 原子替换：保留 mediaId/pageNumber，重置 LQ/transcode
                mediaUpdate.set(Media::getLqStatus, LqStatus.NOT_GENERATED)
                        .set(Media::getTranscodeStatus, TranscodeStatus.NOT_NEEDED);
            }
            mediaMapper.update(null, mediaUpdate);
        }

        UploadSession session = uploadSessionMapper.selectById(ev.targetId());
        if (session != null) {
            comicStatsService.refreshByChapter(session.getChapterId());
            Chapter chapter = chapterMapper.selectById(session.getChapterId());
            if (chapter != null) {
                catalogCacheInvalidator.evict(chapter.getComicId());
            }
        }
        uploadSessionService.cleanupSessionAfterProcessed(ev.targetId());
        log.info("媒体上传/替换完成业务更新: op={}, targetId={}, results={}",
                ev.operationType(), ev.targetId(), ev.results().size());
    }

    /** 上传/替换失败：会话置 FAILED。 */
    public void revertUploadFailed(Long targetId) {
        uploadSessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getId, targetId)
                .set(UploadSession::getStatus, UploadSessionStatus.FAILED));
    }
}
