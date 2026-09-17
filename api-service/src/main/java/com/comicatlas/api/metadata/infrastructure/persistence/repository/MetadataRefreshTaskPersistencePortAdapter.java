package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.MetadataRefreshTaskPersistencePort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 元数据刷新任务持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MetadataRefreshTaskPersistencePortAdapter implements MetadataRefreshTaskPersistencePort {
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;

    @Override
    public int lockComicForRefresh(Long comicId) {
        return comicMapper.lockForMetadataRefresh(comicId);
    }

    @Override
    public int releaseComicRefresh(Long comicId) {
        return comicMapper.releaseMetadataRefresh(comicId);
    }

    @Override
    public List<Long> findComicIdsByChapterIds(List<Long> chapterIds) {
        return chapterMapper.selectBatchIds(chapterIds).stream()
                .map(Chapter::getComicId)
                .toList();
    }
}
