package com.comicatlas.api.upload.service.impl;

// 条件更新由上传结果服务维护跨表状态机与事务边界，Mapper 执行参数化更新。
// 架构说明：Service 直接构造 LambdaUpdateWrapper 更新媒体/上传会话；条件更新应收口到对应 Mapper。
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import com.comicatlas.api.upload.persistence.mapper.UploadFileMapper;
import com.comicatlas.api.upload.persistence.mapper.UploadSessionMapper;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.upload.service.UploadSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
public class UploadCompletionServiceImpl implements com.comicatlas.api.upload.service.UploadCompletionService {
    // 上传结果契约由应用服务公开，具体实现保持在上传业务包内。

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final UploadFileMapper uploadFileMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final UploadSessionService uploadSessionService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ComicStatsService comicStatsService;

    /** 媒体上传/替换完成业务更新：分析结果逐媒体落库 + 统计重算 + 会话清理。 */
    public void applyUploadCompletedBusiness(MediaUploadCompletedEvent ev) {
        UploadSession session = uploadSessionMapper.selectById(ev.targetId());
        if (session == null || session.getStatus() != UploadSessionStatus.COMPLETED) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "上传会话不存在或状态已变化，拒绝确认媒体上传成功: targetId=" + ev.targetId());
        }

        boolean replace = "MEDIA_REPLACE".equals(ev.operationType());
        boolean append = "MEDIA_UPLOAD".equals(ev.operationType());
        if (!replace && !append || replace != (session.getReplaceMediaId() != null)) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "上传事件操作类型与会话不匹配: operation=" + ev.operationType());
        }

        List<UploadFile> uploadedFiles = uploadFileMapper.selectBySessionId(session.getId());
        validateAnalysisResults(ev, session, uploadedFiles, replace);
        for (MediaAnalysisResult result : ev.results()) {
            Media media = new Media();
            media.setId(result.mediaId());
            media.setWidth(result.width());
            media.setHeight(result.height());
            media.setHqSize(result.fileSize());
            media.setMediaType(result.mediaType());
            media.setDuration(result.duration());
            media.setContainer(result.container());
            media.setVideoCodec(result.videoCodec());
            media.setAudioCodec(result.audioCodec());
            media.setHqRoot(result.hqRoot());
            media.setHqPath(result.hqPath());
            int updatedRows = mediaMapper.applyUploadCompleted(media, replace);
            if (updatedRows != 1) {
                throw new BusinessException(HttpStatusCodes.CONFLICT,
                        "媒体上传结果未命中预期记录，拒绝确认任务成功: mediaId=" + result.mediaId());
            }
        }

        comicStatsService.refreshByChapter(session.getChapterId());
        Chapter chapter = chapterMapper.selectById(session.getChapterId());
        if (chapter != null) {
            catalogCacheInvalidator.evict(chapter.getComicId());
        }
        uploadSessionService.cleanupSessionAfterProcessed(ev.targetId());
        log.info("媒体上传/替换完成业务更新: op={}, targetId={}, results={}",
                ev.operationType(), ev.targetId(), ev.results().size());
    }

    /** 确保 Worker 回传结果完整且只对应本次会话预建的媒体行。 */
    private void validateAnalysisResults(MediaUploadCompletedEvent event, UploadSession session,
                                         List<UploadFile> uploadedFiles, boolean replace) {
        List<MediaAnalysisResult> results = event.results();
        if (uploadedFiles.isEmpty() || results == null || results.size() != uploadedFiles.size()) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "Worker 媒体分析结果数量与上传清单不一致: sessionId=" + session.getId());
        }

        Set<Long> expectedMediaIds = new HashSet<>(uploadedFiles.size());
        if (replace) {
            expectedMediaIds.add(session.getReplaceMediaId());
        } else {
            for (UploadFile uploadedFile : uploadedFiles) {
                if (uploadedFile.getMediaId() == null) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT,
                            "上传文件未绑定预建媒体记录: fileId=" + uploadedFile.getFileId());
                }
                expectedMediaIds.add(uploadedFile.getMediaId());
            }
        }

        Set<Long> actualMediaIds = new HashSet<>(results.size());
        for (MediaAnalysisResult result : results) {
            if (result.mediaId() == null || !actualMediaIds.add(result.mediaId())) {
                throw new BusinessException(HttpStatusCodes.CONFLICT,
                        "Worker 媒体分析结果包含空值或重复媒体 ID");
            }
        }
        if (!expectedMediaIds.equals(actualMediaIds)) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "Worker 媒体分析结果与上传会话媒体 ID 不匹配: sessionId=" + session.getId());
        }
    }

    /** 上传/替换失败：会话置 FAILED。 */
    public void revertUploadFailed(Long targetId) {
        uploadSessionMapper.markFailed(targetId);
    }
}
