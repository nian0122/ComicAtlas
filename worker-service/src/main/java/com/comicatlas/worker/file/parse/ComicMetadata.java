package com.comicatlas.worker.file.parse;

import java.util.List;

/**
 * 统一的漫画元数据模型 - 所有导入来源最终汇聚为此结构。
 * 不可变 record，Parser 输出后由 ImportWriter 转换为实体。
 */
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
        List<CatalogInfo> children
    ) {}

    public record ChapterInfo(
        String title,
        String chapterNo,
        int sortOrder,
        int globalOrder,
        Long catalogId,             // 临时 ID（catalogs 列表索引），非 DB 主键
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
