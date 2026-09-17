package com.comicatlas.api.metadata.application.service.impl;

import com.comicatlas.api.catalog.infrastructure.cache.CacheEvictor;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.CategoryDTO;
import com.comicatlas.api.metadata.application.port.in.CategoryManagementService;
import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort;
import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort.CategorySnapshot;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryManagementServiceImpl implements CategoryManagementService {

    private final CategoryPersistencePort persistencePort;
    private final CacheEvictor cacheEvictor;

    @Override
    public List<CategoryDTO> listCategories() {
        return persistencePort.findAllOrdered()
                .stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public CategoryDTO createCategory(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称不能为空");
        }
        String trimmed = name.trim();
        long count = persistencePort.countByName(trimmed);
        if (count > 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类已存在");
        }
        CategorySnapshot category = persistencePort.insert(trimmed, (int) (persistencePort.countAll() + 1));
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
        return toDTO(category);
    }

    @Override
    @Transactional
    public CategoryDTO updateCategory(Long id, String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称不能为空");
        }
        CategorySnapshot category = persistencePort.findById(id).orElse(null);
        if (category == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "分类不存在");
        }
        String trimmed = name.trim();
        long count = persistencePort.countByNameExcludingId(trimmed, id);
        if (count > 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称已存在");
        }
        category = persistencePort.update(id, trimmed, category.sortOrder());
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
        return toDTO(category);
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        if (persistencePort.findById(id).isEmpty()) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "分类不存在");
        }
        persistencePort.deleteById(id);
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
    }

    private CategoryDTO toDTO(CategorySnapshot category) {
        CategoryDTO categoryData = new CategoryDTO();
        categoryData.setId(category.id());
        categoryData.setName(category.name());
        categoryData.setSortOrder(category.sortOrder());
        return categoryData;
    }
}
