package com.comicatlas.api.storage.service;

import java.util.List;

/** 漫画统计聚合服务契约。 */
public interface ComicStatsService {
    void refreshByChapter(Long chapterId);
    void refreshByComic(Long comicId);
    List<Long> chapterIdsOf(Long comicId);
    List<Long> mediaIdsOf(Long comicId);
}
