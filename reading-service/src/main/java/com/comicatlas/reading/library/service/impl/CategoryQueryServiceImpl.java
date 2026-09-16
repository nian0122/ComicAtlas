package com.comicatlas.reading.library.service.impl;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.CategoryDTO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.reading.library.service.CategoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryQueryServiceImpl implements CategoryQueryService {

    private final CategoryMapper categoryMapper;

    @Override
    @Cacheable(
        cacheNames = ComicReferenceCache.CATEGORIES,
        key = "'" + ComicReferenceCache.ALL_KEY + "'",
        unless = "#result == null || #result.isEmpty()")
    public List<CategoryDTO> listCategories() {
        return new ArrayList<>(categoryMapper.selectAllOrderedBySortOrder()
                .stream()
                .map(this::toDTO)
                .sorted(Comparator.comparingInt(category -> category.getSortOrder() == null ? 0 : category.getSortOrder()))
                .toList());
    }

    private CategoryDTO toDTO(Category category) {
        CategoryDTO categoryData = new CategoryDTO();
        categoryData.setId(category.getId());
        categoryData.setName(category.getName());
        categoryData.setSortOrder(category.getSortOrder());
        return categoryData;
    }
}
