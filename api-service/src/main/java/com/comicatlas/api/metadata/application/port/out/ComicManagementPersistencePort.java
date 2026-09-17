package com.comicatlas.api.metadata.application.port.out;

import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Tag;

import java.util.List;

/** 漫画元数据应用服务访问漫画、分类和标签关联的输出端口。 */
public interface ComicManagementPersistencePort {
    Comic findComic(Long comicId);
    List<Tag> findTags(List<Long> tagIds);
    List<Long> findTagIds(Long comicId);
    Category findCategory(Long categoryId);
    void insertComic(Comic comic);
    int updateComic(Comic comic);
    void insertComicTag(ComicTag comicTag);
    int deleteComicTags(Long comicId);
}
