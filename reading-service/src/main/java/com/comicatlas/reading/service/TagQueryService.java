package com.comicatlas.reading.service;

import com.comicatlas.api.comic.dto.TagDTO;

import java.util.List;

/**
 * 标签查询接口（阅读域）。
 * <p>
 * 标签列表用于阅读端筛选；创建/删除由管理服务 {@code TagManagementService} 提供。
 */
public interface TagQueryService {

    List<TagDTO> listTags();
}
