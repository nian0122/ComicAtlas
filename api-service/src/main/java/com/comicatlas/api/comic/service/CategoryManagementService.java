package com.comicatlas.api.comic.service;

import com.comicatlas.contract.comic.dto.CategoryDTO;

/**
 * 分类管理接口（管理域写操作）。
 * <p>
 * 分类查询（列表）由阅读服务 {@code com.comicatlas.reading.service.CategoryQueryService} 提供。
 */
public interface CategoryManagementService {

    /** 创建分类（名称唯一） */
    CategoryDTO createCategory(String name);

    /** 更新分类名称 */
    CategoryDTO updateCategory(Long id, String name);

    /** 删除分类 */
    void deleteCategory(Long id);
}
