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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/comics/{id}/catalog")
    public Result<List<CatalogNode>> getCatalog(@PathVariable Long id) {
        return Result.ok(catalogService.buildTree(id));
    }
}
