package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.CategoryDTO;
import com.comicatlas.api.comic.service.CategoryService;
import com.comicatlas.api.common.Result;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public Result<List<CategoryDTO>> list() {
        return Result.ok(categoryService.listCategories());
    }

    @PostMapping
    public Result<CategoryDTO> create(@RequestParam String name) {
        return Result.ok(categoryService.createCategory(name));
    }

    @PutMapping("/{id}")
    public Result<CategoryDTO> update(@PathVariable Long id, @RequestParam String name) {
        return Result.ok(categoryService.updateCategory(id, name));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return Result.ok();
    }
}
