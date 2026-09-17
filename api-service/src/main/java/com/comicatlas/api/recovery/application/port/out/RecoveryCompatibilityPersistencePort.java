package com.comicatlas.api.recovery.application.port.out;

import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;

import java.util.List;

/** 恢复兼容流程访问漫画及跨域统计数据的输出端口。 */
public interface RecoveryCompatibilityPersistencePort {

    Comic findComic(Long comicId);

    List<Chapter> findChapters(Long comicId);

    long countCatalogs(Long comicId);

    long countImportTasks(Long comicId, List<String> statuses);

    long countMedia(List<Long> chapterIds);

    long countComicTags(Long comicId);

    long countReadingHistory(Long comicId);
}
