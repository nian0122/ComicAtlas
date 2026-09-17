package com.comicatlas.api.recovery.infrastructure.persistence.repository;

import com.comicatlas.api.recovery.application.port.out.RecoveryPersistencePort;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/** 恢复持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class RecoveryPersistencePortAdapter implements RecoveryPersistencePort {
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override public ComicModel findComic(Long comicId) { return copy(comicMapper.selectById(comicId), ComicModel.class); }
    @Override public List<Long> findChapterIds(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId).stream().map(Chapter::getId).toList();
    }
    @Override public void deleteMediaByChapterIds(List<Long> ids) { mediaMapper.deleteByChapterIds(ids); }
    @Override public void deleteChaptersByComicId(Long comicId) { chapterMapper.deleteByComicId(comicId); }
    @Override public void deleteCatalogsByComicId(Long comicId) { catalogMapper.deleteByComicId(comicId); }
    @Override public void insertComic(ComicModel model) { comicMapper.insert(copy(model, Comic.class)); }
    @Override public void updateComic(ComicModel model) { comicMapper.updateById(copy(model, Comic.class)); }
    @Override public void insertCatalog(CatalogModel model) { catalogMapper.insert(copy(model, Catalog.class)); }
    @Override public CatalogModel findCatalog(Long id) { return copy(catalogMapper.selectById(id), CatalogModel.class); }
    @Override public void updateCatalog(CatalogModel model) { catalogMapper.updateById(copy(model, Catalog.class)); }
    @Override public void insertChapter(ChapterModel model) { chapterMapper.insert(copy(model, Chapter.class)); }
    @Override public void updateChapter(ChapterModel model) { chapterMapper.updateById(copy(model, Chapter.class)); }
    @Override public void insertMedia(MediaModel model) { mediaMapper.insert(copy(model, Media.class)); }

    private static <Source, Target> Target copy(Source source, Class<Target> targetType) {
        if (source == null) { return null; }
        Target target = BeanUtils.instantiateClass(targetType);
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
