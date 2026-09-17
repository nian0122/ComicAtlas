package com.comicatlas.api.metadata.application.port.out;

import java.util.List;

/** 元数据刷新任务策略所需的状态持久化端口。 */
public interface MetadataRefreshTaskPersistencePort {
    int lockComicForRefresh(Long comicId);

    int releaseComicRefresh(Long comicId);

    List<Long> findComicIdsByChapterIds(List<Long> chapterIds);
}
