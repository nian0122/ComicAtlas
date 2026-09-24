package com.comicatlas.api.metadata.service.impl;

import com.comicatlas.api.catalog.cache.CacheEvictor;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.api.metadata.service.TagManagementService;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Comparator;
import com.comicatlas.contract.common.util.NaturalNameOrder;

@Service
@RequiredArgsConstructor
public class TagManagementServiceImpl implements TagManagementService {

    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CacheEvictor cacheEvictor;

    @Override
    public List<TagDTO> listTags() {
        return tagMapper.selectList(null).stream()
                .sorted(Comparator.comparing(Tag::getName, NaturalNameOrder.COMPARATOR).thenComparing(Tag::getId))
                .map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public TagDTO createTag(String name) {
        // check duplicate by name
        long count = tagMapper.countByName(name);
        if (count > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "标签已存在: " + name);
        }

        Tag tag = new Tag();
        tag.setName(name);
        tagMapper.insert(tag);
        cacheEvictor.evict(ComicReferenceCache.TAGS, ComicReferenceCache.ALL_KEY);
        cacheEvictor.clear(ComicReferenceCache.COMIC_LIST);
        return toDTO(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "标签不存在");
        }

        // check if tag is bound to any comic
        long boundCount = comicTagMapper.countByTagId(id);
        if (boundCount > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "标签已被漫画使用，无法删除");
        }

        tagMapper.deleteById(id);
        cacheEvictor.evict(ComicReferenceCache.TAGS, ComicReferenceCache.ALL_KEY);
        cacheEvictor.clear(ComicReferenceCache.COMIC_LIST);
    }

    private TagDTO toDTO(Tag tag) {
        TagDTO tagData = new TagDTO();
        tagData.setId(tag.getId());
        tagData.setName(tag.getName());
        return tagData;
    }
}
