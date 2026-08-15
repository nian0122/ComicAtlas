package com.comicatlas.api.comic.service;

import com.comicatlas.contract.comic.dto.BatchUpdateResultVO;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.BatchComicUpdateRequest;
import com.comicatlas.api.comic.dto.ComicMetadataUpdateRequest;
import com.comicatlas.api.comic.dto.ComicTagUpdateRequest;
import com.comicatlas.api.comic.dto.CreateComicRequest;
import com.comicatlas.api.comic.dto.UpdateComicRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;

/**
 * 漫画管理接口（管理域写操作）。
 * <p>
 * 创建/更新/删除漫画、元数据与标签维护、批量更新。漫画查询（列表/详情）由
 * 阅读服务 {@code com.comicatlas.reading.service.ComicQueryService} 提供。
 */
public interface ComicManagementService {

    /** 创建空漫画（DRAFT） */
    ComicDetailVO createComic(CreateComicRequest request);

    /** 乐观锁更新漫画（version 冲突 → 409） */
    ComicDetailVO updateComic(Long id, UpdateComicRequest request);

    /** 删除漫画：创建回收任务而非硬删，返回管理任务 */
    ManagementTaskResponse deleteComic(Long id, String idempotencyKey);

    /** 更新漫画元数据 */
    ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateRequest dto);

    /** 全量覆盖漫画标签绑定关系 */
    void updateComicTags(Long comicId, ComicTagUpdateRequest dto);

    /** 批量更新漫画（分类/标签） */
    BatchUpdateResultVO batchUpdate(BatchComicUpdateRequest dto);
}
