package com.comicatlas.api.catalog.application.port.out;

import java.math.BigDecimal;
import java.util.List;

/** 管理目录树和章节阅读数据查询端口。 */
public interface CatalogStructureQueryPort {
    boolean comicExists(Long comicId);

    List<CatalogSnapshot> findCatalogsByComicOrder(Long comicId);

    List<ChapterSnapshot> findReadyCatalogChapters(Long comicId);

    ChapterSnapshot findChapter(Long chapterId);

    List<MediaSnapshot> findReadyMedia(Long chapterId);

    record CatalogSnapshot(Long id, Long parentId, String title) {
    }

    record ChapterSnapshot(Long id, Long comicId, Long catalogId, String chapterNo, String title,
                           Integer globalOrder, Integer pageCount, String status) {
    }

    record MediaSnapshot(Long id, Integer pageNumber, String hqRoot, String hqPath, String lqRoot,
                         String lqPath, String hqStatus, String lqStatus, Integer width, Integer height,
                         Long hqSize, Long lqSize, String mediaType, BigDecimal duration, String container,
                         String videoCodec, String audioCodec, String transcodeStatus) {
    }
}
