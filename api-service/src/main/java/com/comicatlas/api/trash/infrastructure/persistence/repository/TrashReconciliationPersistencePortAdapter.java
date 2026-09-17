package com.comicatlas.api.trash.infrastructure.persistence.repository;

import com.comicatlas.api.trash.application.port.out.TrashReconciliationPersistencePort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 回收对账实体状态端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class TrashReconciliationPersistencePortAdapter implements TrashReconciliationPersistencePort {
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override public Comic findComic(Long comicId) { return comicMapper.selectById(comicId); }
    @Override public Chapter findChapter(Long chapterId) { return chapterMapper.selectById(chapterId); }
    @Override public Media findMedia(Long mediaId) { return mediaMapper.selectById(mediaId); }
    @Override public void updateComic(Comic comic) { comicMapper.updateById(comic); }
    @Override public void updateChapter(Chapter chapter) { chapterMapper.updateById(chapter); }
    @Override public void updateMedia(Media media) { mediaMapper.updateById(media); }
}
