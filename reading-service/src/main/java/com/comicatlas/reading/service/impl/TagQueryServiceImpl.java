package com.comicatlas.reading.service.impl;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.reading.service.TagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TagQueryServiceImpl implements TagQueryService {

    private final TagMapper tagMapper;

    @Override
    @Cacheable(
        cacheNames = ComicReferenceCache.TAGS,
        key = "'" + ComicReferenceCache.ALL_KEY + "'",
        unless = "#result == null || #result.isEmpty()")
    public List<TagDTO> listTags() {
        List<Tag> tags = tagMapper.selectList(null);
        return new ArrayList<>(tags.stream().map(this::toDTO).toList());
    }

    private TagDTO toDTO(Tag tag) {
        TagDTO dto = new TagDTO();
        dto.setId(tag.getId());
        dto.setName(tag.getName());
        return dto;
    }
}
