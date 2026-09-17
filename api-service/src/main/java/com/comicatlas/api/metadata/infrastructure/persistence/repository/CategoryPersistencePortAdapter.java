package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.CategoryPersistencePort;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 分类持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class CategoryPersistencePortAdapter implements CategoryPersistencePort {
    private final CategoryMapper categoryMapper;

    @Override public List<CategorySnapshot> findAllOrdered() {
        return categoryMapper.selectAllOrderedBySortOrder().stream()
                .map(category -> new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder()))
                .toList();
    }
    @Override public long countByName(String name) { return categoryMapper.countByName(name); }
    @Override public long countByNameExcludingId(String name, Long categoryId) {
        return categoryMapper.countByNameExcludingId(name, categoryId);
    }
    @Override public long countAll() { return categoryMapper.countAll(); }
    @Override public Optional<CategorySnapshot> findById(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        return Optional.ofNullable(category)
                .map(value -> new CategorySnapshot(value.getId(), value.getName(), value.getSortOrder()));
    }
    @Override public CategorySnapshot insert(String name, int sortOrder) {
        Category category = new Category();
        category.setName(name);
        category.setSortOrder(sortOrder);
        categoryMapper.insert(category);
        return new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder());
    }
    @Override public CategorySnapshot update(Long categoryId, String name, Integer sortOrder) {
        Category category = new Category();
        category.setId(categoryId);
        category.setName(name);
        category.setSortOrder(sortOrder);
        categoryMapper.updateById(category);
        return new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder());
    }
    @Override public void deleteById(Long categoryId) { categoryMapper.deleteById(categoryId); }
}
