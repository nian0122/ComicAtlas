package com.comicatlas.reading.library.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.comic.dto.CategoryDTO;
import com.comicatlas.reading.library.service.CategoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类查询接口（阅读域）。
 * <p>
 * 基路径 {@code /api/categories}，提供分类列表供阅读端筛选。
 * 分类管理（创建/重命名/删除）由管理服务提供。
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryQueryController {

    private final CategoryQueryService categoryQueryService;

    /**
     * 查询全部分类（按 sortOrder 排序）。
     *
     * @return 分类列表
     */
    @GetMapping
    public Result<List<CategoryDTO>> list() {
        return Result.ok(categoryQueryService.listCategories());
    }
}
