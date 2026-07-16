package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.CategoryDTO;
import com.comicatlas.api.comic.service.CategoryService;
import com.comicatlas.api.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
