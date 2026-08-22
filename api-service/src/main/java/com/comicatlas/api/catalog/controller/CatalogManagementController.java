package com.comicatlas.api.catalog.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.catalog.dto.CatalogVO;
import com.comicatlas.api.catalog.dto.CatalogCreateRequest;
import com.comicatlas.api.catalog.dto.CatalogMoveRequest;
import com.comicatlas.api.catalog.dto.CatalogRenameRequest;
import com.comicatlas.api.catalog.dto.CatalogReorderRequest;
import com.comicatlas.api.catalog.service.CatalogManagementService;
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
@RequestMapping("/api/manage/comics/{comicId}/catalogs")
@RequiredArgsConstructor
public class CatalogManagementController {

    private final CatalogManagementService catalogManagementService;

    /**
     * 在指定漫画下创建目录。
     *
     * @param request 目录信息（标题/父目录/排序）
     * @return 创建的目录
     */
    @PostMapping
    public Result<CatalogVO> create(
            @PathVariable Long comicId,
            @Valid @RequestBody CatalogCreateRequest request) {
        return Result.ok(catalogManagementService.createCatalog(comicId, request));
    }

    /**
     * 重命名目录，同级重名时返回 409。
     *
     * @param request 新标题
     * @return 重命名后的目录
     */
    @PatchMapping("/{catalogId}")
    public Result<CatalogVO> rename(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @Valid @RequestBody CatalogRenameRequest request) {
        return Result.ok(catalogManagementService.renameCatalog(comicId, catalogId, request));
    }

    /**
     * 移动目录到目标父目录下（祖先检查防环）。
     *
     * @param request 目标父目录 ID；请求体缺省或 parentId 为 null 表示移到根级
     * @return 移动后的目录
     */
    @PutMapping("/{catalogId}/move")
    public Result<CatalogVO> move(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @RequestBody(required = false) CatalogMoveRequest request) {
        Long parentId = request != null ? request.getParentId() : null;
        return Result.ok(catalogManagementService.moveCatalog(comicId, catalogId, parentId));
    }

    /**
     * 同级目录重排（结果保持连续 1..N）。
     *
     * @param request 目标 sortOrder（1 基）
     * @return 空结果
     */
    @PutMapping("/{catalogId}/reorder")
    public Result<Void> reorder(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @Valid @RequestBody CatalogReorderRequest request) {
        catalogManagementService.reorderCatalog(comicId, catalogId, request.getSortOrder());
        return Result.ok();
    }

    /**
     * 删除目录；非空目录必须显式指定 reparentTo 重挂子目录/章节，否则 409。
     *
     * @param reparentTo 子目录与章节重挂的目标目录 ID；空目录可省略
     * @return 空结果
     */
    @DeleteMapping("/{catalogId}")
    public Result<Void> delete(
            @PathVariable Long comicId,
            @PathVariable Long catalogId,
            @RequestParam(required = false) Long reparentTo) {
        catalogManagementService.deleteCatalog(comicId, catalogId, reparentTo);
        return Result.ok();
    }
}
