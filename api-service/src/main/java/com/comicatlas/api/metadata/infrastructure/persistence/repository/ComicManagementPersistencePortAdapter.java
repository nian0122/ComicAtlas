package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.ComicManagementPersistencePort;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 漫画元数据持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class ComicManagementPersistencePortAdapter implements ComicManagementPersistencePort {
    private final ComicMapper comicMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;

    @Override public Comic findComic(Long comicId) { return comicMapper.selectById(comicId); }
    @Override public List<Tag> findTags(List<Long> tagIds) { return tagMapper.selectBatchIds(tagIds); }
    @Override public List<Long> findTagIds(Long comicId) { return comicTagMapper.selectTagIdsByComicId(comicId); }
    @Override public Category findCategory(Long categoryId) { return categoryMapper.selectById(categoryId); }
    @Override public void insertComic(Comic comic) { comicMapper.insert(comic); }
    @Override public int updateComic(Comic comic) { return comicMapper.updateById(comic); }
    @Override public void insertComicTag(ComicTag comicTag) { comicTagMapper.insert(comicTag); }
    @Override public int deleteComicTags(Long comicId) { return comicTagMapper.deleteByComicId(comicId); }
}
