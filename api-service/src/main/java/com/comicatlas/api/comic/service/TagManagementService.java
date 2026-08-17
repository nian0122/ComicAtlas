package com.comicatlas.api.comic.service;

import com.comicatlas.contract.comic.dto.TagDTO;
import java.util.List;

/**
 * 标签管理接口（管理域写操作）。
 * <p>
 * 标签查询（列表）由阅读服务 {@code com.comicatlas.reading.service.TagQueryService} 提供。
 */
public interface TagManagementService {
    List<TagDTO> listTags();

    /** 创建标签（名称唯一，重复返回 409） */
    TagDTO createTag(String name);

    /** 删除标签；已被漫画引用时返回 409 */
    void deleteTag(Long id);
}
