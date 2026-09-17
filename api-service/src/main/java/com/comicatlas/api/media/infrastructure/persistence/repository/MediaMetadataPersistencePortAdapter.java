package com.comicatlas.api.media.infrastructure.persistence.repository;

import com.comicatlas.api.media.application.port.out.MediaMetadataPersistencePort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 媒体元数据持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class MediaMetadataPersistencePortAdapter implements MediaMetadataPersistencePort {
    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;

    @Override
    public MediaSnapshot findMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        return media == null ? null : new MediaSnapshot(media.getId(), media.getChapterId());
    }

    @Override
    public ChapterSnapshot findChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : new ChapterSnapshot(chapter.getId(), chapter.getComicId());
    }

    @Override
    public int updateMedia(MediaUpdateCommand command) {
        Media media = new Media();
        media.setId(command.id());
        media.setWidth(command.width());
        media.setHeight(command.height());
        media.setDuration(command.duration());
        media.setContainer(command.container());
        media.setVideoCodec(command.videoCodec());
        media.setAudioCodec(command.audioCodec());
        return mediaMapper.updateById(media);
    }
}
