package com.comicatlas.api.media.application.port.out;

import java.util.List;
import java.math.BigDecimal;

/** 媒体操作完成处理所需的持久化端口。 */
public interface MediaOperationPersistencePort {
    int resetLqNotGeneratedByChapter(Long chapterId);
    List<MediaSnapshot> findHqDeleteCandidates(Long chapterId);
    MediaSnapshot findMedia(Long mediaId);
    int updateLqReadyBatch(Long chapterId, List<LqReadyUpdate> mediaItems);
    int markLqFailedByChapter(Long chapterId);
    int markHqDeleteFailed(Long mediaId);
    int markTranscodeFailed(Long mediaId);
    int transitionLqGenerating(Long chapterId);
    int transitionHqDeleting(Long chapterId);
    int transitionTranscoding(Long mediaId);
    int markHqDeleted(Long mediaId);
    int applyTranscodeCompleted(Long mediaId, String container, String videoCodec, String audioCodec,
                                BigDecimal duration, Long fileSize, String newHqPath);

    record MediaSnapshot(Long id, String hqPath) {
    }

    record LqReadyUpdate(Long id, String lqRoot, String lqPath, Long lqSize) {
    }
}
