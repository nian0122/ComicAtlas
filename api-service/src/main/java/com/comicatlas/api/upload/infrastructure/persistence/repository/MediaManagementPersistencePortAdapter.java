package com.comicatlas.api.upload.infrastructure.persistence.repository;

import com.comicatlas.api.upload.application.port.out.MediaManagementPersistencePort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 媒体管理持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class MediaManagementPersistencePortAdapter implements MediaManagementPersistencePort {
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override public MediaManagementPersistencePort.ChapterSnapshot findChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : new MediaManagementPersistencePort.ChapterSnapshot(chapter.getId());
    }
    @Override public List<MediaManagementPersistencePort.MediaSnapshot> findMediaByChapter(Long chapterId) {
        return mediaMapper.selectByChapterId(chapterId).stream()
                .map(media -> new MediaManagementPersistencePort.MediaSnapshot(
                        media.getId(), media.getChapterId(), media.getPageNumber(), media.getVersion()))
                .toList();
    }
    @Override public MediaManagementPersistencePort.MediaSnapshot findMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        return media == null ? null : new MediaManagementPersistencePort.MediaSnapshot(
                media.getId(), media.getChapterId(), media.getPageNumber(), media.getVersion());
    }
    @Override public int movePageNumbersToTemporaryNegative(Long chapterId) {
        return mediaMapper.updatePageNumberToTemporaryNegative(chapterId);
    }
    @Override public int updateMedia(MediaManagementPersistencePort.MediaPageNumberUpdateCommand command) {
        Media media = new Media();
        media.setId(command.id());
        media.setPageNumber(command.pageNumber());
        media.setVersion(command.version());
        return mediaMapper.updateById(media);
    }
}
