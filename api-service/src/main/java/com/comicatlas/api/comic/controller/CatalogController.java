package com.comicatlas.api.comic.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
