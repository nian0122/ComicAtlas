package com.comicatlas.api.catalog.infrastructure.persistence.repository;

import com.comicatlas.api.catalog.application.port.out.CatalogCommandPersistencePort;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 目录写端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class CatalogCommandPersistencePortAdapter implements CatalogCommandPersistencePort {
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;

    @Override
    public ComicSnapshot findComic(Long comicId) {
        return comicMapper.selectById(comicId) == null ? null : new ComicSnapshot(comicId);
    }

    @Override
    public ChapterSnapshot findChapter(Long chapterId) {
        return toChapterSnapshot(chapterMapper.selectById(chapterId));
    }

    @Override
    public ChapterSnapshot insertChapter(ChapterCommand command) {
        Chapter chapter = toChapter(command);
        chapterMapper.insert(chapter);
        return toChapterSnapshot(chapter);
    }

    @Override
    public List<ChapterSnapshot> findChaptersByComicOrder(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId).stream()
                .map(CatalogCommandPersistencePortAdapter::toChapterSnapshot).toList();
    }

    @Override
    public void updateGlobalOrderToTemporaryNegative(Long comicId) {
        chapterMapper.updateGlobalOrderToTemporaryNegative(comicId);
    }

    @Override
    public ChapterSnapshot findLastChapterByComic(Long comicId) {
        return toChapterSnapshot(chapterMapper.selectLastByComicId(comicId));
    }

    @Override
    public CatalogSnapshot findCatalog(Long catalogId) {
        Catalog catalog = catalogMapper.selectById(catalogId);
        return catalog == null ? null : toCatalogSnapshot(catalog);
    }

    @Override
    public CatalogSnapshot insertCatalog(CatalogCommand command) {
        Catalog catalog = toCatalog(command);
        catalogMapper.insert(catalog);
        return toCatalogSnapshot(catalog);
    }

    @Override
    public int updateCatalog(CatalogCommand command) {
        return catalogMapper.updateById(toCatalog(command));
    }

    @Override
    public void deleteCatalog(Long catalogId) {
        catalogMapper.deleteById(catalogId);
    }

    @Override
    public List<CatalogSnapshot> findChildren(Long comicId, Long parentId) {
        return catalogMapper.selectChildrenByComicIdAndParentId(comicId, parentId).stream()
                .map(CatalogCommandPersistencePortAdapter::toCatalogSnapshot).toList();
    }

    @Override
    public List<CatalogSnapshot> findRootCatalogs(Long comicId) {
        return catalogMapper.selectRootByComicId(comicId).stream()
                .map(CatalogCommandPersistencePortAdapter::toCatalogSnapshot).toList();
    }

    @Override
    public List<ChapterSnapshot> findChapters(Long comicId, Long catalogId) {
        return chapterMapper.selectByComicIdAndCatalogId(comicId, catalogId).stream()
                .map(CatalogCommandPersistencePortAdapter::toChapterSnapshot).toList();
    }

    @Override
    public int updateChapter(ChapterCommand command) {
        return chapterMapper.updateById(toChapter(command));
    }

    @Override
    public ChapterSnapshot findLastChapterWithoutCatalog(Long comicId) {
        return toChapterSnapshot(chapterMapper.selectLastByComicIdWithoutCatalog(comicId));
    }

    @Override
    public ChapterSnapshot findLastChapter(Long comicId, Long catalogId) {
        return toChapterSnapshot(chapterMapper.selectLastByComicIdAndCatalogId(comicId, catalogId));
    }

    private static Catalog toCatalog(CatalogCommand command) {
        Catalog catalog = new Catalog();
        catalog.setId(command.id());
        catalog.setComicId(command.comicId());
        catalog.setParentId(command.parentId());
        catalog.setTitle(command.title());
        catalog.setSortOrder(command.sortOrder());
        return catalog;
    }

    private static Chapter toChapter(ChapterCommand command) {
        Chapter chapter = new Chapter();
        chapter.setId(command.id());
        chapter.setComicId(command.comicId());
        chapter.setCatalogId(command.catalogId());
        chapter.setTitle(command.title());
        chapter.setChapterNo(command.chapterNo());
        chapter.setSortOrder(command.sortOrder());
        chapter.setGlobalOrder(command.globalOrder());
        chapter.setStatus(command.status());
        chapter.setVersion(command.version());
        return chapter;
    }

    private static CatalogSnapshot toCatalogSnapshot(Catalog catalog) {
        return new CatalogSnapshot(catalog.getId(), catalog.getComicId(), catalog.getParentId(),
                catalog.getTitle(), catalog.getSortOrder());
    }

    private static ChapterSnapshot toChapterSnapshot(Chapter chapter) {
        return chapter == null ? null : new ChapterSnapshot(chapter.getId(), chapter.getComicId(),
                chapter.getCatalogId(), chapter.getTitle(), chapter.getChapterNo(), chapter.getPageCount(),
                chapter.getSortOrder(), chapter.getGlobalOrder(), chapter.getStatus(), chapter.getVersion());
    }
}
