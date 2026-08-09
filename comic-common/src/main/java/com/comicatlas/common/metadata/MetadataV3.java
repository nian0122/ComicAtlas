package com.comicatlas.common.metadata;

import com.comicatlas.common.storage.RelativePathValidator;
import com.fasterxml.jackson.annotation.JsonInclude;

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
                            BigDecimal duration, String container, String videoCodec, String audioCodec,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String hqPath) {

        public MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, String mediaType, Integer width, Integer height,
                         BigDecimal duration, String container, String videoCodec, String audioCodec,
                         String hqPath) {
            RelativePathValidator.requireRelativeForwardSlash(hqPath);
            this.fileName = fileName;
            this.pageNumber = pageNumber;
            this.hqStatus = hqStatus;
            this.lqStatus = lqStatus;
            this.fileSize = fileSize;
            this.mediaType = mediaType;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.container = container;
            this.videoCodec = videoCodec;
            this.audioCodec = audioCodec;
            this.hqPath = hqPath;
        }

        /** 旧构造入口（无 hqPath），保持向后兼容。 */
        public MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, String mediaType, Integer width, Integer height,
                         BigDecimal duration, String container, String videoCodec, String audioCodec) {
            this(fileName, pageNumber, hqStatus, lqStatus, fileSize, mediaType, width, height,
                    duration, container, videoCodec, audioCodec, null);
        }
    }
}
