package com.comicatlas.api.upload.application.service.impl;

// 条件更新由上传结果服务维护跨表状态机与事务边界，Mapper 执行参数化更新。
// 架构说明：Service 直接构造 LambdaUpdateWrapper 更新媒体/上传会话；条件更新应收口到对应 Mapper。
import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.storage.application.port.in.ComicStatsService;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.api.upload.application.port.in.UploadSessionService;
import com.comicatlas.api.upload.application.port.out.UploadCompletionPersistencePort;
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
public class UploadCompletionServiceImpl implements com.comicatlas.api.upload.application.port.in.UploadCompletionService {
    // 上传结果契约由应用服务公开，具体实现保持在上传业务包内。

    private final UploadCompletionPersistencePort persistencePort;
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
            persistencePort.applyMediaCompleted(new UploadCompletionPersistencePort.MediaCompletedCommand(
                    result.mediaId(), result.width(), result.height(), result.fileSize(), result.mediaType(),
                    result.duration(), result.container(), result.videoCodec(), result.audioCodec(),
                    result.hqRoot(), result.hqPath()), replace);
        }

        UploadCompletionPersistencePort.UploadSessionSnapshot session = persistencePort.findSession(ev.targetId());
        if (session != null) {
            comicStatsService.refreshByChapter(session.chapterId());
            UploadCompletionPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(session.chapterId());
            if (chapter != null) {
                catalogCacheInvalidator.evict(chapter.comicId());
            }
        }
        uploadSessionService.cleanupSessionAfterProcessed(ev.targetId());
        log.info("媒体上传/替换完成业务更新: op={}, targetId={}, results={}",
                ev.operationType(), ev.targetId(), ev.results().size());
    }

    /** 上传/替换失败：会话置 FAILED。 */
    public void revertUploadFailed(Long targetId) {
        persistencePort.markSessionFailed(targetId);
    }
}
