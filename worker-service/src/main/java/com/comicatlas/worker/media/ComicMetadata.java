package com.comicatlas.worker.media;

import com.comicatlas.common.constant.MediaTypes;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.math.BigDecimal;
import java.util.List;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ComicMetadata {
    private final String title;
    private final String author;
    private final String category;
    private final List<String> tags;
    private final String description;
    private final List<CatalogInfo> catalogs;
    private final List<ChapterInfo> chapters;

    public ComicMetadata(String title, String author, String category, List<String> tags,
                         String description, List<CatalogInfo> catalogs, List<ChapterInfo> chapters) {
        this.title = title;
        this.author = author;
        this.category = category;
        this.tags = tags;
        this.description = description;
        this.catalogs = catalogs;
        this.chapters = chapters;
    }

    public String title() { return title; }
    public String author() { return author; }
    public String category() { return category; }
    public List<String> tags() { return tags; }
    public String description() { return description; }
    public List<CatalogInfo> catalogs() { return catalogs; }
    public List<ChapterInfo> chapters() { return chapters; }

    /** 兼容无简介字段的旧构造方式。 */
    public ComicMetadata(String title, String author, String category, List<String> tags,
                         List<CatalogInfo> catalogs, List<ChapterInfo> chapters) {
        this(title, author, category, tags, null, catalogs, chapters);
    }
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class CatalogInfo {
        private final String title;
        private final int sortOrder;
        private final Integer parentIndex;

        public CatalogInfo(String title, int sortOrder, Integer parentIndex) {
            this.title = title;
            this.sortOrder = sortOrder;
            this.parentIndex = parentIndex;
        }

        public String title() { return title; }
        public int sortOrder() { return sortOrder; }
        public Integer parentIndex() { return parentIndex; }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class ChapterInfo {
        private final String title;
        private final String chapterNo;
        private final int sortOrder;
        private final int globalOrder;
        private final Integer catalogIndex;
        private final String sourceDir;
        private final List<MediaInfo> pages;

        public ChapterInfo(String title, String chapterNo, int sortOrder, int globalOrder,
                           Integer catalogIndex, String sourceDir, List<MediaInfo> pages) {
            this.title = title;
            this.chapterNo = chapterNo;
            this.sortOrder = sortOrder;
            this.globalOrder = globalOrder;
            this.catalogIndex = catalogIndex;
            this.sourceDir = sourceDir;
            this.pages = pages;
        }

        public String title() { return title; }
        public String chapterNo() { return chapterNo; }
        public int sortOrder() { return sortOrder; }
        public int globalOrder() { return globalOrder; }
        public Integer catalogIndex() { return catalogIndex; }
        public String sourceDir() { return sourceDir; }
        public List<MediaInfo> pages() { return pages; }
    }

    /**
     * 媒体项元数据（图片 + 视频）。
     * fieldName 取代 imageName 以兼容图片和视频。
     * 视频字段（duration/container/videoCodec/audioCodec）仅 VIDEO 媒体有值。
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MediaInfo {
        private final String fileName;
        private final int pageNumber;
        private final String hqStatus;
        private final String lqStatus;
        private final long fileSize;
        private final Integer width;
        private final Integer height;
        private final String mediaType;
        private final BigDecimal duration;
        private final String container;
        private final String videoCodec;
        private final String audioCodec;
        private final String format;
        private final Boolean decodable;
        private final Boolean needsConversion;
        private final String conversionStatus;
        private final String conversionError;

        public MediaInfo(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, Integer width, Integer height, String mediaType,
                         BigDecimal duration, String container, String videoCodec, String audioCodec,
                         String format, Boolean decodable, Boolean needsConversion,
                         String conversionStatus, String conversionError) {
            this.fileName = fileName;
            this.pageNumber = pageNumber;
            this.hqStatus = hqStatus;
            this.lqStatus = lqStatus;
            this.fileSize = fileSize;
            this.width = width;
            this.height = height;
            this.mediaType = mediaType;
            this.duration = duration;
            this.container = container;
            this.videoCodec = videoCodec;
            this.audioCodec = audioCodec;
            this.format = format;
            this.decodable = decodable;
            this.needsConversion = needsConversion;
            this.conversionStatus = conversionStatus;
            this.conversionError = conversionError;
        }

        public String fileName() { return fileName; }
        public int pageNumber() { return pageNumber; }
        public String hqStatus() { return hqStatus; }
        public String lqStatus() { return lqStatus; }
        public long fileSize() { return fileSize; }
        public Integer width() { return width; }
        public Integer height() { return height; }
        public String mediaType() { return mediaType; }
        public BigDecimal duration() { return duration; }
        public String container() { return container; }
        public String videoCodec() { return videoCodec; }
        public String audioCodec() { return audioCodec; }
        public String format() { return format; }
        public Boolean decodable() { return decodable; }
        public Boolean needsConversion() { return needsConversion; }
        public String conversionStatus() { return conversionStatus; }
        public String conversionError() { return conversionError; }
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
