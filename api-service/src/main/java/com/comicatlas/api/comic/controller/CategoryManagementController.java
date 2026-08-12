package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.CategoryDTO;
import com.comicatlas.api.comic.service.CategoryManagementService;
import com.comicatlas.api.common.Result;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 分类管理接口（管理域）。
 * <p>
 * 基路径 {@code /api/manage/categories}，提供分类创建、重命名与删除。
 * 分类名称唯一（重名返回 400）。分类查询（列表）由阅读服务提供。
 */
@RestController
@RequestMapping("/api/manage/categories")
@RequiredArgsConstructor
public class CategoryManagementController {

    private final CategoryManagementService categoryManagementService;

    /**
     * 创建分类，名称重复时返回 400。
     *
     * @param name 分类名称
     * @return 创建的分类
     */
    @PostMapping
    public Result<CategoryDTO> create(@RequestParam String name) {
        return Result.ok(categoryManagementService.createCategory(name));
    }

    /**
     * 重命名分类，与其他分类重名时返回 400。
     *
     * @param name 新分类名称
     * @return 更新后的分类
     */
    @PutMapping("/{id}")
    public Result<CategoryDTO> update(@PathVariable Long id, @RequestParam String name) {
        return Result.ok(categoryManagementService.updateCategory(id, name));
    }

    /**
     * 删除分类。
     *
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryManagementService.deleteCategory(id);
        return Result.ok();
    }
}
