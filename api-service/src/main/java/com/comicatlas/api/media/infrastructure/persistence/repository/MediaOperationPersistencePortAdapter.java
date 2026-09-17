package com.comicatlas.api.media.infrastructure.persistence.repository;

import com.comicatlas.api.media.application.port.out.MediaOperationPersistencePort;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.math.BigDecimal;

/** 媒体操作持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MediaOperationPersistencePortAdapter implements MediaOperationPersistencePort {
    private final MediaMapper mediaMapper;

    @Override public int resetLqNotGeneratedByChapter(Long chapterId) {
        return mediaMapper.resetLqNotGeneratedByChapter(chapterId);
    }
    @Override public List<MediaSnapshot> findHqDeleteCandidates(Long chapterId) {
        return mediaMapper.selectHqDeleteCandidates(chapterId).stream()
                .map(media -> new MediaSnapshot(media.getId(), media.getHqPath())).toList();
    }
    @Override public MediaSnapshot findMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        return media == null ? null : new MediaSnapshot(media.getId(), media.getHqPath());
    }
    @Override public int updateLqReadyBatch(Long chapterId, List<LqReadyUpdate> mediaItems) {
        List<Media> persistenceItems = mediaItems.stream().map(item -> {
            Media media = new Media();
            media.setId(item.id());
            media.setLqStatus(com.comicatlas.contract.common.enums.LqStatus.READY);
            media.setLqRoot(item.lqRoot());
            media.setLqPath(item.lqPath());
            media.setLqSize(item.lqSize());
            return media;
        }).toList();
        return mediaMapper.updateLqReadyBatch(chapterId, persistenceItems);
    }
    @Override public int markLqFailedByChapter(Long chapterId) {
        return mediaMapper.markLqFailedByChapter(chapterId);
    }
    @Override public int markHqDeleteFailed(Long mediaId) { return mediaMapper.markHqDeleteFailed(mediaId); }
    @Override public int markTranscodeFailed(Long mediaId) { return mediaMapper.markTranscodeFailed(mediaId); }
    @Override public int transitionLqGenerating(Long chapterId) {
        return mediaMapper.transitionLqGenerating(chapterId);
    }
    @Override public int transitionHqDeleting(Long chapterId) { return mediaMapper.transitionHqDeleting(chapterId); }
    @Override public int transitionTranscoding(Long mediaId) { return mediaMapper.transitionTranscoding(mediaId); }
    @Override public int markHqDeleted(Long mediaId) { return mediaMapper.markHqDeleted(mediaId); }
    @Override public int applyTranscodeCompleted(Long mediaId, String container, String videoCodec,
                                                 String audioCodec, BigDecimal duration, Long fileSize,
                                                 String newHqPath) {
        return mediaMapper.applyTranscodeCompleted(mediaId, container, videoCodec, audioCodec,
                duration, fileSize, newHqPath);
    }
}
