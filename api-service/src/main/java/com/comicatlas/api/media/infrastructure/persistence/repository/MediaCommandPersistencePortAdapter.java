package com.comicatlas.api.media.infrastructure.persistence.repository;

import com.comicatlas.api.media.application.port.out.MediaCommandPersistencePort;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 媒体命令持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MediaCommandPersistencePortAdapter implements MediaCommandPersistencePort {
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final MediaMapper mediaMapper;
    @Override public ComicSnapshot findComic(Long id) {
        Comic comic = comicMapper.selectById(id);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getStatus());
    }
    @Override public ChapterSnapshot findChapter(Long id) {
        return chapterMapper.selectById(id) == null ? null : new ChapterSnapshot(id);
    }
    @Override public List<ChapterSnapshot> findChapters(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId).stream()
                .map(chapter -> new ChapterSnapshot(chapter.getId())).toList();
    }
    @Override public List<MediaSnapshot> findImages(Long chapterId) {
        return mediaMapper.selectImagesByChapterId(chapterId).stream().map(MediaCommandPersistencePortAdapter::toSnapshot).toList();
    }
    @Override public List<MediaSnapshot> findDeletableImages(List<Long> chapterIds) {
        return mediaMapper.selectDeletableImagesByChapterIds(chapterIds).stream()
                .map(MediaCommandPersistencePortAdapter::toSnapshot).toList();
    }
    @Override public long countDeletableImages(Long chapterId) { return mediaMapper.countDeletableImagesByChapterId(chapterId); }
    @Override public List<MediaSnapshot> findVideosByChapters(List<Long> chapterIds) { return mediaMapper.selectVideosByChapterIds(chapterIds).stream().map(MediaCommandPersistencePortAdapter::toSnapshot).toList(); }
    @Override public List<MediaSnapshot> findVideos(Long chapterId) { return mediaMapper.selectVideosByChapterId(chapterId).stream().map(MediaCommandPersistencePortAdapter::toSnapshot).toList(); }
    @Override public MediaSnapshot findMedia(Long mediaId) { Media media = mediaMapper.selectById(mediaId); return media == null ? null : toSnapshot(media); }
    @Override public int markLqQueued(Long chapterId) { return mediaMapper.markLqQueued(chapterId); }
    @Override public int markHqDeleteQueued(Long chapterId) { return mediaMapper.markHqDeleteQueuedByChapter(chapterId); }
    @Override public int markHqDeleteQueued(List<Long> chapterIds) { return mediaMapper.markHqDeleteQueued(chapterIds); }
    @Override public int markTranscodeQueued(Long mediaId) { return mediaMapper.markTranscodeQueued(mediaId); }
    @Override public int markTranscodeNotNeeded(Long mediaId) { return mediaMapper.markTranscodeNotNeeded(mediaId); }

    private static MediaSnapshot toSnapshot(Media media) {
        return new MediaSnapshot(media.getId(), media.getChapterId(), media.getPageNumber(), media.getMediaType(),
                media.getHqStatus(), media.getLqStatus(), media.getTranscodeStatus(), media.getWidth(),
                media.getHeight(), media.getVideoCodec(), media.getContainer());
    }
}
