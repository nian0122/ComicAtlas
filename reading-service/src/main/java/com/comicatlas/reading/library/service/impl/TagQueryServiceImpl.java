package com.comicatlas.reading.library.service.impl;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.reading.library.service.TagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import com.comicatlas.contract.common.util.NaturalNameOrder;

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
        return new ArrayList<>(tags.stream()
                .sorted(Comparator.comparing(Tag::getName, NaturalNameOrder.COMPARATOR).thenComparing(Tag::getId))
                .map(this::toDTO).toList());
    }

    private TagDTO toDTO(Tag tag) {
        TagDTO tagData = new TagDTO();
        tagData.setId(tag.getId());
        tagData.setName(tag.getName());
        return tagData;
    }
}
