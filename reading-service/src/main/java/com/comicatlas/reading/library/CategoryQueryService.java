package com.comicatlas.reading.library;

import com.comicatlas.contract.comic.dto.CategoryDTO;

import java.util.List;

/**
 * 分类查询接口（阅读域）。
 * <p>
 * 分类列表用于阅读端筛选；创建/重命名/删除由管理服务 {@code CategoryManagementService} 提供。
 */
public interface CategoryQueryService {

    List<CategoryDTO> listCategories();
}
