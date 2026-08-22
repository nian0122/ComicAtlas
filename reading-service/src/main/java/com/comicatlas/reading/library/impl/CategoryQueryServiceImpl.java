package com.comicatlas.reading.library.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.CategoryDTO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.reading.library.CategoryQueryService;
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
        return new ArrayList<>(categoryMapper.selectList(new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder))
                .stream()
                .map(this::toDTO)
                .sorted(Comparator.comparingInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()))
                .toList());
    }

    private CategoryDTO toDTO(Category c) {
        CategoryDTO dto = new CategoryDTO();
        dto.setId(c.getId());
        dto.setName(c.getName());
        dto.setSortOrder(c.getSortOrder());
        return dto;
    }
}
