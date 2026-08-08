package com.comicatlas.api.comic.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.service.CatalogService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 漫画目录树查询接口。
 * <p>
 * 基路径 {@code /api}，提供单本漫画的目录树（多级目录 + 章节），供前端目录导航渲染。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    /**
     * 构建漫画目录树。
     *
     * @param id 漫画 ID
     * @return 目录树节点列表（嵌套结构）
     */
    @GetMapping("/comics/{id}/catalog")
    public Result<List<CatalogNode>> getCatalog(@PathVariable Long id) {
        return Result.ok(catalogService.buildTree(id));
    }
}
