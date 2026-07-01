package com.comicatlas.api.comic.dto;

import java.util.List;

public record CatalogNode(
    Long id,
    String title,
    List<CatalogNode> children,
    List<ChapterRef> chapters
) {}
