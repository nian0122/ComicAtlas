package com.comicatlas.common.metadata;

import java.math.BigDecimal;
import java.util.List;

/** metadata v3 通用模型 — 与 ComicAtlas v3 格式一一对应，各模块映射实体后共享构建。 */
public record MetadataV3(
        Comic comic,
        List<Catalog> catalogs,
        List<Chapter> chapters) {

    /** category/tags 为可选（api 侧输出，worker 侧为 null）。 */
    public record Comic(String title, String author, String category, List<String> tags) {}

    public record Catalog(String title, int sortOrder, Integer parentIndex) {}

    public record Chapter(String title, String chapterNo, int sortOrder, int globalOrder,
                          Integer catalogIndex, List<MediaItem> mediaItems) {}

    /** width/height/duration/container/videoCodec/audioCodec 可选（null 时不输出）。 */
    public record MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                            long fileSize, String mediaType, Integer width, Integer height,
                            BigDecimal duration, String container, String videoCodec, String audioCodec) {}
}
