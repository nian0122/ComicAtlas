package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.TagPersistencePort;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 标签持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class TagPersistencePortAdapter implements TagPersistencePort {
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;

    @Override public List<TagSnapshot> findAll() {
        return tagMapper.selectList(null).stream()
                .map(tag -> new TagSnapshot(tag.getId(), tag.getName()))
                .toList();
    }
    @Override public long countByName(String name) { return tagMapper.countByName(name); }
    @Override public Optional<TagSnapshot> findById(Long tagId) {
        Tag tag = tagMapper.selectById(tagId);
        return Optional.ofNullable(tag).map(value -> new TagSnapshot(value.getId(), value.getName()));
    }
    @Override public long countComicBindings(Long tagId) { return comicTagMapper.countByTagId(tagId); }
    @Override public TagSnapshot insert(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tagMapper.insert(tag);
        return new TagSnapshot(tag.getId(), tag.getName());
    }
    @Override public void deleteById(Long tagId) { tagMapper.deleteById(tagId); }
}
