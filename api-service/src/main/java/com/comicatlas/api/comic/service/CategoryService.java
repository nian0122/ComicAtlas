package com.comicatlas.api.comic.service;

import com.comicatlas.api.comic.dto.CategoryDTO;

import java.util.List;

public interface CategoryService {
    List<CategoryDTO> listCategories();

    CategoryDTO createCategory(String name);

    CategoryDTO updateCategory(Long id, String name);

    void deleteCategory(Long id);
}
