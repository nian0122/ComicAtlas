package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort.CategorySnapshot;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.entity.Tag;
import java.util.List;

/** 导入落库应用服务所需的统一持久化端口。 */
public interface ImportPersistencePort {
    ImportTask findImportTask(Long taskId);
    Comic findComic(Long comicId);
    Comic findComicForUpdate(Long comicId);
    void updateComic(Comic comic);
    void updateImportTask(ImportTask task);
    long countChapters(Long comicId);
    List<Long> findTagIds(Long comicId);
    Tag findTag(String name, String type);
    void insertTag(Tag tag);
    void insertComicTag(ComicTag comicTag);
    List<CategorySnapshot> findCategories();
    void insertCatalog(Catalog catalog);
    void updateCatalog(Catalog catalog);
    void insertChapter(Chapter chapter);
    List<Chapter> findChapters(Long comicId);
    Chapter findChapter(Long chapterId);
    void updateChapter(Chapter chapter);
    int insertMediaBatch(List<Media> mediaItems);
    int markMediaFinalized(Long chapterId, String hqRelativePath);
    long countPendingMedia(List<Long> chapterIds, String hqStatus);
    List<Media> findAllMedia(List<Long> chapterIds);
}
