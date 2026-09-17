package com.comicatlas.api.catalog.application.port.out;

import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;

import java.util.List;

/** 目录写用例使用的持久化端口，仅暴露应用快照与写命令。 */
public interface CatalogCommandPersistencePort {
    ComicSnapshot findComic(Long comicId);

    ChapterSnapshot findChapter(Long chapterId);

    ChapterSnapshot insertChapter(ChapterCommand command);

    List<ChapterSnapshot> findChaptersByComicOrder(Long comicId);

    void updateGlobalOrderToTemporaryNegative(Long comicId);

    ChapterSnapshot findLastChapterByComic(Long comicId);

    CatalogSnapshot findCatalog(Long catalogId);

    CatalogSnapshot insertCatalog(CatalogCommand command);

    int updateCatalog(CatalogCommand command);

    void deleteCatalog(Long catalogId);

    List<CatalogSnapshot> findChildren(Long comicId, Long parentId);

    List<CatalogSnapshot> findRootCatalogs(Long comicId);

    List<ChapterSnapshot> findChapters(Long comicId, Long catalogId);

    int updateChapter(ChapterCommand command);

    ChapterSnapshot findLastChapterWithoutCatalog(Long comicId);

    ChapterSnapshot findLastChapter(Long comicId, Long catalogId);

    record ComicSnapshot(Long id) {
    }

    record CatalogSnapshot(Long id, Long comicId, Long parentId, String title, Integer sortOrder) {
    }

    record ChapterSnapshot(Long id, Long comicId, Long catalogId, String title, String chapterNo,
                           Integer pageCount, Integer sortOrder, Integer globalOrder,
                           ChapterLifecycleStatus status, Integer version) {
    }

    record CatalogCommand(Long id, Long comicId, Long parentId, String title, Integer sortOrder) {
    }

    record ChapterCommand(Long id, Long comicId, Long catalogId, String title, String chapterNo,
                          Integer sortOrder, Integer globalOrder, ChapterLifecycleStatus status,
                          Integer version) {
    }
}
