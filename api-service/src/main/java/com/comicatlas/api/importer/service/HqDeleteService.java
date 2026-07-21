package com.comicatlas.api.importer.service;
public interface HqDeleteService {
    HqDeleteResult deleteForComic(Long comicId);
    HqDeleteResult deleteForChapter(Long chapterId);
}
