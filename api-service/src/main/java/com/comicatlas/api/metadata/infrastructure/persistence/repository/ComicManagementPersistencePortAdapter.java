package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.ComicManagementPersistencePort;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
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

    @Override public ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getTitle(), comic.getTitleJpn(),
                comic.getAuthor(), comic.getDescription(), comic.getStatus(), comic.getStoragePolicy(),
                comic.getVersion(), comic.getCategoryId(), comic.getCategory());
    }
    @Override public List<TagSnapshot> findTags(List<Long> tagIds) {
        return tagMapper.selectBatchIds(tagIds).stream().map(tag -> new TagSnapshot(tag.getId())).toList();
    }
    @Override public List<Long> findTagIds(Long comicId) { return comicTagMapper.selectTagIdsByComicId(comicId); }
    @Override public CategorySnapshot findCategory(Long categoryId) {
        var category = categoryMapper.selectById(categoryId);
        return category == null ? null : new CategorySnapshot(category.getId(), category.getName());
    }
    @Override public Long insertComic(ComicCreateCommand command) {
        Comic comic = new Comic();
        comic.setTitle(command.title());
        comic.setTitleJpn(command.titleJpn());
        comic.setAuthor(command.author());
        comic.setDescription(command.description());
        comic.setStatus(command.status());
        comic.setStoragePolicy(command.storagePolicy());
        comic.setVersion(command.version());
        comic.setCategoryId(command.categoryId());
        comic.setCategory(command.category());
        comicMapper.insert(comic);
        return comic.getId();
    }
    @Override public int updateComic(ComicUpdateCommand command) {
        Comic comic = new Comic();
        comic.setId(command.id());
        comic.setTitle(command.title());
        comic.setTitleJpn(command.titleJpn());
        comic.setAuthor(command.author());
        comic.setDescription(command.description());
        comic.setStatus(command.status());
        comic.setStoragePolicy(command.storagePolicy());
        comic.setVersion(command.version());
        comic.setCategoryId(command.categoryId());
        comic.setCategory(command.category());
        return comicMapper.updateById(comic);
    }
    @Override public void insertComicTag(ComicTagCommand command) {
        ComicTag comicTag = new ComicTag();
        comicTag.setComicId(command.comicId());
        comicTag.setTagId(command.tagId());
        comicTagMapper.insert(comicTag);
    }
    @Override public int deleteComicTags(Long comicId) { return comicTagMapper.deleteByComicId(comicId); }
}
