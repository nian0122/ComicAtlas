package com.comicatlas.worker.media;

import java.math.BigDecimal;
import java.util.List;

public record ComicMetadata(
    String title,
    String author,
    String category,
    List<String> tags,
    String description,
    List<CatalogInfo> catalogs,
    List<ChapterInfo> chapters
) {
    /** 兼容无简介字段的旧构造方式。 */
    public ComicMetadata(String title, String author, String category, List<String> tags,
                         List<CatalogInfo> catalogs, List<ChapterInfo> chapters) {
        this(title, author, category, tags, null, catalogs, chapters);
    }
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
        List<MediaInfo> pages
    ) {}

    /**
     * 媒体项元数据（图片 + 视频）。
     * fieldName 取代 imageName 以兼容图片和视频。
     * 视频字段（duration/container/videoCodec/audioCodec）仅 VIDEO 媒体有值。
     */
    public record MediaInfo(
        String fileName,
        int pageNumber,
        String hqStatus,
        String lqStatus,
        long fileSize,
        Integer width,
        Integer height,
        String mediaType,
        BigDecimal duration,
        String container,
        String videoCodec,
        String audioCodec,
        String format,
        Boolean decodable,
        Boolean needsConversion,
        String conversionStatus,
        String conversionError
    ) {
        /**
         * 向后兼容构造函数：仅传入图片场景的 7 个参数，
         * 其余视频字段默认为 null，mediaType 默认为 "IMAGE"。
         * 用于调用方零改动接入图片场景。
         */
        public MediaInfo(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, Integer width, Integer height) {
            this(fileName, pageNumber, hqStatus, lqStatus, fileSize, width, height,
                 MediaTypes.IMAGE, null, null, null, null);
        }

        public MediaInfo(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, Integer width, Integer height, String mediaType,
                         BigDecimal duration, String container, String videoCodec, String audioCodec) {
            this(fileName, pageNumber, hqStatus, lqStatus, fileSize, width, height, mediaType,
                    duration, container, videoCodec, audioCodec, null, null, null, null, null);
        }

        /**
         * 复制并替换 pageNumber，其余字段保持不变。
         * MediaAnalyzer.analyze() 返回的 pageNumber 默认为 0，
         * 由 MetadataAssembler 按章节顺序填充为 i+1。
         */
        public MediaInfo withPageNumber(int pageNumber) {
            return new MediaInfo(fileName, pageNumber, hqStatus, lqStatus,
                    fileSize, width, height, mediaType, duration,
                    container, videoCodec, audioCodec, format, decodable,
                    needsConversion, conversionStatus, conversionError);
        }

        /**
         * 向后兼容访问器：旧代码使用 imageName()，新代码应使用 fileName()。
         */
        @Deprecated
        public String imageName() {
            return fileName;
        }
    }
}
