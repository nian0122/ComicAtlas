package com.comicatlas.api.comic.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.CatalogCreateRequest;
import com.comicatlas.api.comic.dto.CatalogMoveRequest;
import com.comicatlas.api.comic.dto.CatalogRenameRequest;
import com.comicatlas.api.comic.dto.CatalogReorderRequest;
import com.comicatlas.api.comic.dto.CatalogVO;
import com.comicatlas.api.comic.service.CatalogManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目录管理端点（create / rename / move / reorder / delete）。
 *
 * <p>所有请求携带 comicId，用于校验 path ID 属于同一漫画。
 */
@RestController
@RequestMapping("/api/comics/{comicId}/catalogs")
@RequiredArgsConstructor
public class CatalogManagementController {

    private final CatalogManagementService catalogManagementService;

    @PostMapping
    public Result<CatalogVO> create(
            @PathVariable Long comicId,
            @Valid @RequestBody CatalogCreateRequest request) {
        return Result.ok(catalogManagementService.createCatalog(comicId, request));
    }

    @PatchMapping("/{catalogId}")
    public Result<CatalogVO> rename(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @Valid @RequestBody CatalogRenameRequest request) {
        return Result.ok(catalogManagementService.renameCatalog(comicId, catalogId, request));
    }

    @PutMapping("/{catalogId}/move")
    public Result<CatalogVO> move(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @RequestBody(required = false) CatalogMoveRequest request) {
        Long parentId = request != null ? request.getParentId() : null;
        return Result.ok(catalogManagementService.moveCatalog(comicId, catalogId, parentId));
    }

    @PutMapping("/{catalogId}/reorder")
    public Result<Void> reorder(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @Valid @RequestBody CatalogReorderRequest request) {
        catalogManagementService.reorderCatalog(comicId, catalogId, request.getSortOrder());
        return Result.ok();
    }

    @DeleteMapping("/{catalogId}")
    public Result<Void> delete(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @RequestParam(required = false) Long reparentTo) {
        catalogManagementService.deleteCatalog(comicId, catalogId, reparentTo);
        return Result.ok();
    }
}
