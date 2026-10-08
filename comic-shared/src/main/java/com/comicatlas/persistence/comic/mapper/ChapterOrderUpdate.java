package com.comicatlas.persistence.comic.mapper;

/** 单章目标全局顺序与目录内顺序。 */
public record ChapterOrderUpdate(Long chapterId, int globalOrder, int sortOrder) {
}
