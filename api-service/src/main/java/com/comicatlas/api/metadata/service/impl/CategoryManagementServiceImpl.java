package com.comicatlas.api.metadata.service.impl;

import com.comicatlas.api.catalog.cache.CacheEvictor;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.CategoryDTO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.api.metadata.service.CategoryManagementService;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryManagementServiceImpl implements CategoryManagementService {

    private final CategoryMapper categoryMapper;
    private final CacheEvictor cacheEvictor;

    @Override
    public List<CategoryDTO> listCategories() {
        return categoryMapper.selectAllOrderedBySortOrder()
                .stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public CategoryDTO createCategory(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称不能为空");
        }
        String trimmed = name.trim();
        long count = categoryMapper.countByName(trimmed);
        if (count > 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类已存在");
        }
        Category category = new Category();
        category.setName(trimmed);
        category.setSortOrder((int) (categoryMapper.countAll() + 1));
        categoryMapper.insert(category);
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
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "分类不存在");
        }
        String trimmed = name.trim();
        long count = categoryMapper.countByNameExcludingId(trimmed, id);
        if (count > 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称已存在");
        }
        category.setName(trimmed);
        categoryMapper.updateById(category);
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
        return toDTO(category);
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "分类不存在");
        }
        categoryMapper.deleteById(id);
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
        cacheEvictor.evictComicList();
    }

    private CategoryDTO toDTO(Category category) {
        CategoryDTO categoryData = new CategoryDTO();
        categoryData.setId(category.getId());
        categoryData.setName(category.getName());
        categoryData.setSortOrder(category.getSortOrder());
        return categoryData;
    }
}
