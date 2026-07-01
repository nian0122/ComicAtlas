package com.comicatlas.api.comic.dto;

public record ChapterRef(
    Long id,
    String chapterNo,
    String title,
    int globalOrder,
    int pageCount,
    String status
) {}
