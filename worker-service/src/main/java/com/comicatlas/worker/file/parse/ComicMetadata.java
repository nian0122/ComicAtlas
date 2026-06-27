package com.comicatlas.worker.file.parse;

import java.util.List;
import java.util.Map;

/**
 * 统一的漫画元数据模型 - 所有导入来源最终汇聚为此结构
 */
public record ComicMetadata(
    String title,
    String author,
    String category,
    List<String> tags,
    List<ChapterInfo> chapters,
    String storageType,
    String rootKey,
    String relativePath
) {
    public record ChapterInfo(
        String title,
        String chapterNo,
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
