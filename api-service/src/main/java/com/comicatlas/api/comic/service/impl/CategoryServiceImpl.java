package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.cache.CacheEvictor;
import com.comicatlas.api.comic.cache.ComicReferenceCache;
import com.comicatlas.api.comic.dto.CategoryDTO;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.service.CategoryService;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final CacheEvictor cacheEvictor;

    @Override
    @Cacheable(
        cacheNames = ComicReferenceCache.CATEGORIES,
        key = "'" + ComicReferenceCache.ALL_KEY + "'",
        unless = "#result == null || #result.isEmpty()")
    public List<CategoryDTO> listCategories() {
        return new ArrayList<>(categoryMapper.selectList(new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder))
                .stream()
                .map(this::toDTO)
                .sorted(Comparator.comparingInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()))
                .toList());
    }

    @Override
    @Transactional
    public CategoryDTO createCategory(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称不能为空");
        }
        String trimmed = name.trim();
        Long count = categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>().eq(Category::getName, trimmed));
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类已存在");
        }
        Category category = new Category();
        category.setName(trimmed);
        category.setSortOrder((int) (categoryMapper.selectCount(new LambdaQueryWrapper<>()) + 1));
        categoryMapper.insert(category);
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
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
        Long count = categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>().eq(Category::getName, trimmed).ne(Category::getId, id));
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类名称已存在");
        }
        category.setName(trimmed);
        categoryMapper.updateById(category);
        cacheEvictor.evict(ComicReferenceCache.CATEGORIES, ComicReferenceCache.ALL_KEY);
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
    }

    private CategoryDTO toDTO(Category c) {
        CategoryDTO dto = new CategoryDTO();
        dto.setId(c.getId());
        dto.setName(c.getName());
        dto.setSortOrder(c.getSortOrder());
        return dto;
    }
}
