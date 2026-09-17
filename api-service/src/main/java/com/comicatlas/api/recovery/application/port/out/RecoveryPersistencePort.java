package com.comicatlas.api.recovery.application.port.out;

import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import lombok.Data;

import java.util.List;

/** 恢复用例的持久化端口，隔离 MyBatis 实体与恢复编排。 */
public interface RecoveryPersistencePort {
    ComicModel findComic(Long comicId);
    List<Long> findChapterIds(Long comicId);
    void deleteMediaByChapterIds(List<Long> chapterIds);
    void deleteChaptersByComicId(Long comicId);
    void deleteCatalogsByComicId(Long comicId);
    void insertComic(ComicModel comic);
    void updateComic(ComicModel comic);
    void insertCatalog(CatalogModel catalog);
    CatalogModel findCatalog(Long catalogId);
    void updateCatalog(CatalogModel catalog);
    void insertChapter(ChapterModel chapter);
    void updateChapter(ChapterModel chapter);
    void insertMedia(MediaModel media);

    @Data
    class ComicModel {
        private Long id;
        private String title;
        private String author;
        private String category;
        private ComicStatus status;
        private String storagePolicy;
        private Integer totalPages;
        private Long hqSize;
    }

    @Data
    class CatalogModel {
        private Long id;
        private Long comicId;
        private Long parentId;
        private String title;
        private Integer sortOrder;
    }

    @Data
    class ChapterModel {
        private Long id;
        private Long comicId;
        private Long catalogId;
        private String title;
        private String chapterNo;
        private Integer pageCount;
        private Integer sortOrder;
        private Integer globalOrder;
        private ChapterLifecycleStatus status;
    }

    @Data
    class MediaModel {
        private Long id;
        private Long chapterId;
        private Integer pageNumber;
        private String hqRoot;
        private String hqPath;
        private HqStatus hqStatus;
        private LqStatus lqStatus;
        private Long lqSize;
        private String lqRoot;
        private String lqPath;
        private Long hqSize;
        private Integer width;
        private Integer height;
        private String mediaType;
    }
}
