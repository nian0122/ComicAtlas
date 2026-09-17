package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.comicatlas.api.task.application.port.out.MediaOperationEligibilityPersistencePort;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 媒体操作资格资产读取端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MediaOperationEligibilityPersistencePortAdapter
        implements MediaOperationEligibilityPersistencePort {
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final MediaMapper mediaMapper;

    @Override public ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getStatus());
    }
    @Override public List<ChapterSnapshot> findChapters(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId).stream()
                .map(chapter -> new ChapterSnapshot(chapter.getId())).toList();
    }
    @Override public List<MediaSnapshot> findMediaByChapters(List<Long> chapterIds) {
        return mediaMapper.selectByChapterIds(chapterIds).stream()
                .map(MediaOperationEligibilityPersistencePortAdapter::toSnapshot).toList();
    }
    @Override public List<MediaSnapshot> findMediaByChapter(Long chapterId) {
        return mediaMapper.selectByChapterId(chapterId).stream()
                .map(MediaOperationEligibilityPersistencePortAdapter::toSnapshot).toList();
    }
    @Override public MediaSnapshot findMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        return media == null ? null : toSnapshot(media);
    }

    private static MediaSnapshot toSnapshot(Media media) {
        return new MediaSnapshot(media.getId(), media.getChapterId(), media.getMediaType(), media.getHqStatus(),
                media.getLqStatus(), media.getTranscodeStatus(), media.getWidth(), media.getHeight(),
                media.getVideoCodec(), media.getContainer(), media.getStatus());
    }
}
