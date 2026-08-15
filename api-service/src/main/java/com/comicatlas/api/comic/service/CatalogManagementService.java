package com.comicatlas.api.comic.service;

import com.comicatlas.contract.comic.dto.CatalogVO;
import com.comicatlas.api.comic.dto.CatalogCreateRequest;
import com.comicatlas.api.comic.dto.CatalogRenameRequest;

/**
 * 目录管理服务：create / rename / move / reorder / delete。
 *
 * <p>约束：
 * <ul>
 *   <li>所有 path ID 必须属于同一漫画，跨漫画一律 409</li>
 *   <li>目录 move 做祖先检查防环（parent → descendant 拒绝）</li>
 *   <li>非空目录删除必须显式 {@code reparentTo}，否则 409</li>
 *   <li>同级 sort_order 重排后连续 1..N</li>
 * </ul>
 */
public interface CatalogManagementService {

    CatalogVO createCatalog(Long comicId, CatalogCreateRequest request);

    CatalogVO renameCatalog(Long comicId, Long catalogId, CatalogRenameRequest request);

    /** @param newParentId null 表示移动到根 */
    CatalogVO moveCatalog(Long comicId, Long catalogId, Long newParentId);

    void reorderCatalog(Long comicId, Long catalogId, int newSortOrder);

    /** @param reparentTo 非空目录删除时必须显式指定；null 且非空 → 409 */
    void deleteCatalog(Long comicId, Long catalogId, Long reparentTo);
}
