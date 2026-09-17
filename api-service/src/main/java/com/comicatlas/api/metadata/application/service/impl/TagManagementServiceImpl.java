package com.comicatlas.api.metadata.application.service.impl;

import com.comicatlas.api.catalog.infrastructure.cache.CacheEvictor;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.api.metadata.application.port.in.TagManagementService;
import com.comicatlas.api.metadata.application.port.out.TagPersistencePort;
import com.comicatlas.api.metadata.application.port.out.TagPersistencePort.TagSnapshot;
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

    private final TagPersistencePort persistencePort;
    private final CacheEvictor cacheEvictor;

    @Override
    public List<TagDTO> listTags() {
        return persistencePort.findAll().stream()
                .sorted(Comparator.comparing(TagSnapshot::name, NaturalNameOrder.COMPARATOR)
                        .thenComparing(TagSnapshot::id))
                .map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public TagDTO createTag(String name) {
        // check duplicate by name
        long count = persistencePort.countByName(name);
        if (count > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "标签已存在: " + name);
        }

        TagSnapshot tag = persistencePort.insert(name);
        cacheEvictor.evict(ComicReferenceCache.TAGS, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
        return toDTO(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        if (persistencePort.findById(id).isEmpty()) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "标签不存在");
        }

        // check if tag is bound to any comic
        long boundCount = persistencePort.countComicBindings(id);
        if (boundCount > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "标签已被漫画使用，无法删除");
        }

        persistencePort.deleteById(id);
        cacheEvictor.evict(ComicReferenceCache.TAGS, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
    }

    private TagDTO toDTO(TagSnapshot tag) {
        TagDTO tagData = new TagDTO();
        tagData.setId(tag.id());
        tagData.setName(tag.name());
        return tagData;
    }
}
