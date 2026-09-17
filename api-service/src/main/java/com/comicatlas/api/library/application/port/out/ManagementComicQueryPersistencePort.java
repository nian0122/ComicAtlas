package com.comicatlas.api.library.application.port.out;

import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.SourceType;

import java.util.List;

/** 管理端漫画查询的持久化输出端口。 */
public interface ManagementComicQueryPersistencePort {
    ComicPageSnapshot findPage(long pageNumber, long pageSize, ComicListQuery query);
    ComicSnapshot findComic(Long comicId);
    List<Long> findTagIds(Long comicId);
    CategorySnapshot findCategory(Long categoryId);

    /** 管理列表所需的漫画查询快照。 */
    record ComicListSnapshot(Long id, String title, String author, Integer totalPages,
                             Long categoryId, com.comicatlas.contract.common.enums.ComicStatus status,
                             java.time.LocalDateTime createdAt) {
    }

    /** 分页结果快照，隔离 MyBatis-Plus 分页类型。 */
    record ComicPageSnapshot(long total, List<ComicListSnapshot> records) {
    }

    /** 分类查询快照。 */
    record CategorySnapshot(Long id, String name) {
    }

    /** 漫画基础详情快照。 */
    record ComicSnapshot(Long id, String title, String titleJpn, String author, String description,
                         Integer totalPages, Long hqSize, SourceType sourceType, String sourceRef,
                         Long categoryId, ComicStatus status, Integer version,
                         java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
    }
}
