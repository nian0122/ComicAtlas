package com.comicatlas.api.recovery.infrastructure.persistence.repository;

import com.comicatlas.api.recovery.application.port.out.RecoveryCompatibilityPersistencePort;
import com.comicatlas.api.recovery.infrastructure.persistence.mapper.RecoveryDataMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 恢复兼容持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class RecoveryCompatibilityPersistencePortAdapter implements RecoveryCompatibilityPersistencePort {

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final RecoveryDataMapper recoveryDataMapper;

    @Override
    public Comic findComic(Long comicId) { return comicMapper.selectById(comicId); }

    @Override
    public List<Chapter> findChapters(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
    }

    @Override
    public long countCatalogs(Long comicId) { return catalogMapper.countByComicId(comicId); }

    @Override
    public long countImportTasks(Long comicId, List<String> statuses) {
        return recoveryDataMapper.countImportTasks(comicId, statuses);
    }

    @Override
    public long countMedia(List<Long> chapterIds) {
        return recoveryDataMapper.countMediaByChapterIds(chapterIds);
    }

    @Override
    public long countComicTags(Long comicId) { return recoveryDataMapper.countComicTags(comicId); }

    @Override
    public long countReadingHistory(Long comicId) { return recoveryDataMapper.countReadingHistory(comicId); }
}
