package com.comicatlas.api.task.infrastructure.persistence.repository;

import com.comicatlas.api.task.application.port.out.BatchPersistencePort;
import com.comicatlas.contract.comic.dto.ComicListQuery;
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
import java.util.Optional;

/** 批量任务持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class BatchPersistencePortAdapter implements BatchPersistencePort {
    private final ComicMapper comicMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;

    @Override public Optional<String> findComicStatus(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? Optional.empty() : Optional.ofNullable(comic.getStatus()).map(Enum::name);
    }
    @Override public ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getTitle(), comic.getAuthor(),
                comic.getDescription(), comic.getCategoryId(), comic.getCategory());
    }
    @Override public List<Long> findComicIds(ComicListQuery query, int limit) {
        return comicMapper.selectIdsByQuery(query, limit);
    }
    @Override public CategorySnapshot findCategory(Long categoryId) {
        return Optional.ofNullable(categoryMapper.selectById(categoryId))
                .map(category -> new CategorySnapshot(category.getId(), category.getName())).orElse(null);
    }
    @Override public List<Long> findExistingTagIds(List<Long> tagIds) {
        return tagMapper.selectBatchIds(tagIds).stream().map(Tag::getId).toList();
    }
    @Override public List<Long> findTagIds(Long comicId) { return comicTagMapper.selectTagIdsByComicId(comicId); }
    @Override public void updateComic(ComicUpdateCommand command) {
        Comic comic = new Comic();
        comic.setId(command.id());
        comic.setTitle(command.title());
        comic.setAuthor(command.author());
        comic.setDescription(command.description());
        comic.setCategoryId(command.categoryId());
        comic.setCategory(command.category());
        comicMapper.updateById(comic);
    }
    @Override public void insertComicTag(Long comicId, Long tagId) {
        ComicTag comicTag = new ComicTag();
        comicTag.setComicId(comicId);
        comicTag.setTagId(tagId);
        comicTagMapper.insert(comicTag);
    }
}
