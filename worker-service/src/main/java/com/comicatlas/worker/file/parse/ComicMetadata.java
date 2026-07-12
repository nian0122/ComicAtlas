package com.comicatlas.worker.file.parse;

import java.util.List;

public record ComicMetadata(
    String title,
    String author,
    String category,
    List<String> tags,
    List<CatalogInfo> catalogs,
    List<ChapterInfo> chapters
) {
    public record CatalogInfo(
        String title,
        int sortOrder,
        Integer parentIndex
    ) {}

    public record ChapterInfo(
        String title,
        String chapterNo,
        int sortOrder,
        int globalOrder,
        Integer catalogIndex,
        String sourceDir,
        List<PageInfo> pages
    ) {}

    public record PageInfo(
        String imageName,
        int pageNumber,
        String hqStatus,
        String lqStatus,
        long fileSize,
        Integer width,
        Integer height
    ) {}
}
