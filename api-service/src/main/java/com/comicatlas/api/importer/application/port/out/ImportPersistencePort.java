package com.comicatlas.api.importer.application.port.out;

import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort.CategorySnapshot;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.CatalogModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ChapterModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ComicModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ComicTagModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.ImportTaskModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.MediaModel;
import com.comicatlas.api.importer.application.port.out.ImportPersistenceModel.TagModel;
import java.util.List;

/** 导入落库应用服务所需的统一持久化端口。 */
public interface ImportPersistencePort {
    ImportTaskModel findImportTask(Long taskId);
    ComicModel findComic(Long comicId);
    ComicModel findComicForUpdate(Long comicId);
    void updateComic(ComicModel comic);
    void updateImportTask(ImportTaskModel task);
    long countChapters(Long comicId);
    List<Long> findTagIds(Long comicId);
    TagModel findTag(String name, String type);
    void insertTag(TagModel tag);
    void insertComicTag(ComicTagModel comicTag);
    List<CategorySnapshot> findCategories();
    void insertCatalog(CatalogModel catalog);
    void updateCatalog(CatalogModel catalog);
    void insertChapter(ChapterModel chapter);
    List<ChapterModel> findChapters(Long comicId);
    ChapterModel findChapter(Long chapterId);
    void updateChapter(ChapterModel chapter);
    int insertMediaBatch(List<MediaModel> mediaItems);
    int markMediaFinalized(Long chapterId, String hqRelativePath);
    long countPendingMedia(List<Long> chapterIds, String hqStatus);
    List<MediaModel> findAllMedia(List<Long> chapterIds);
}
