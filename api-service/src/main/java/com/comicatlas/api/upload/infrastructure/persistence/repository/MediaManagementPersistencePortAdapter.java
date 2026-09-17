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

    @Override public Chapter findChapter(Long chapterId) { return chapterMapper.selectById(chapterId); }
    @Override public List<Media> findMediaByChapter(Long chapterId) {
        return mediaMapper.selectByChapterId(chapterId);
    }
    @Override public Media findMedia(Long mediaId) { return mediaMapper.selectById(mediaId); }
    @Override public int movePageNumbersToTemporaryNegative(Long chapterId) {
        return mediaMapper.updatePageNumberToTemporaryNegative(chapterId);
    }
    @Override public int updateMedia(Media media) { return mediaMapper.updateById(media); }
}
