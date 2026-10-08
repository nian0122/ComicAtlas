package com.comicatlas.common.metadata;

import com.comicatlas.common.storage.RelativePathValidator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/** metadata v3 通用模型 — 与 ComicAtlas v3 格式一一对应，各模块映射实体后共享构建。 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MetadataV3 {
    private final Comic comic;
    private final List<Catalog> catalogs;
    private final List<Chapter> chapters;

    @JsonCreator
    public MetadataV3(@JsonProperty("comic") Comic comic, @JsonProperty("catalogs") List<Catalog> catalogs,
                      @JsonProperty("chapters") List<Chapter> chapters) {
        this.comic = comic;
        this.catalogs = catalogs;
        this.chapters = chapters;
    }

    public Comic comic() { return comic; }
    public List<Catalog> catalogs() { return catalogs; }
    public List<Chapter> chapters() { return chapters; }

    /** category/tags 为可选（api 侧输出，worker 侧为 null）。 */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Comic {
        private final String title;
        private final String author;
        private final String category;
        private final List<String> tags;
        private final String description;

        @JsonCreator
        public Comic(@JsonProperty("title") String title, @JsonProperty("author") String author,
                     @JsonProperty("category") String category, @JsonProperty("tags") List<String> tags,
                     @JsonProperty("description") String description) {
            this.title = title;
            this.author = author;
            this.category = category;
            this.tags = tags;
            this.description = description;
        }

        public String title() { return title; }
        public String author() { return author; }
        public String category() { return category; }
        public List<String> tags() { return tags; }
        public String description() { return description; }

        public Comic(String title, String author, String category, List<String> tags) {
            this(title, author, category, tags, null);
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Catalog {
        private final String title;
        private final int sortOrder;
        private final Integer parentIndex;

        @JsonCreator
        public Catalog(@JsonProperty("title") String title, @JsonProperty("sortOrder") int sortOrder,
                       @JsonProperty("parentIndex") Integer parentIndex) {
            this.title = title;
            this.sortOrder = sortOrder;
            this.parentIndex = parentIndex;
        }

        public String title() { return title; }
        public int sortOrder() { return sortOrder; }
        public Integer parentIndex() { return parentIndex; }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Chapter {
        private final String title;
        private final String chapterNo;
        private final int sortOrder;
        private final int globalOrder;
        private final Integer catalogIndex;
        private final List<MediaItem> mediaItems;

        @JsonCreator
        public Chapter(@JsonProperty("title") String title, @JsonProperty("chapterNo") String chapterNo,
                       @JsonProperty("sortOrder") int sortOrder, @JsonProperty("globalOrder") int globalOrder,
                       @JsonProperty("catalogIndex") Integer catalogIndex,
                       @JsonProperty("mediaItems") List<MediaItem> mediaItems) {
            this.title = title;
            this.chapterNo = chapterNo;
            this.sortOrder = sortOrder;
            this.globalOrder = globalOrder;
            this.catalogIndex = catalogIndex;
            this.mediaItems = mediaItems;
        }

        public String title() { return title; }
        public String chapterNo() { return chapterNo; }
        public int sortOrder() { return sortOrder; }
        public int globalOrder() { return globalOrder; }
        public Integer catalogIndex() { return catalogIndex; }
        public List<MediaItem> mediaItems() { return mediaItems; }
    }

    /** width/height/duration/container/videoCodec/audioCodec 可选（null 时不输出）；
     *  lqSize 为 LQ 文件字节数（未生成时为 0），lqPath 可推导（hqPath 换 .webp），无需记录。 */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MediaItem {
        private final String fileName;
        private final int pageNumber;
        private final String hqStatus;
        private final String lqStatus;
        private final long fileSize;
        private final String mediaType;
        private final Integer width;
        private final Integer height;
        private final BigDecimal duration;
        private final String container;
        private final String videoCodec;
        private final String audioCodec;
        private final long lqSize;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String hqPath;

        @JsonCreator
        public MediaItem(@JsonProperty("fileName") String fileName, @JsonProperty("pageNumber") int pageNumber,
                         @JsonProperty("hqStatus") String hqStatus, @JsonProperty("lqStatus") String lqStatus,
                         @JsonProperty("fileSize") long fileSize, @JsonProperty("mediaType") String mediaType,
                         @JsonProperty("width") Integer width, @JsonProperty("height") Integer height,
                         @JsonProperty("duration") BigDecimal duration, @JsonProperty("container") String container,
                         @JsonProperty("videoCodec") String videoCodec, @JsonProperty("audioCodec") String audioCodec,
                         @JsonProperty("lqSize") long lqSize, @JsonProperty("hqPath") String hqPath) {
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
            this.lqSize = lqSize;
            this.hqPath = hqPath;
        }

        public String fileName() { return fileName; }
        public int pageNumber() { return pageNumber; }
        public String hqStatus() { return hqStatus; }
        public String lqStatus() { return lqStatus; }
        public long fileSize() { return fileSize; }
        public String mediaType() { return mediaType; }
        public Integer width() { return width; }
        public Integer height() { return height; }
        public BigDecimal duration() { return duration; }
        public String container() { return container; }
        public String videoCodec() { return videoCodec; }
        public String audioCodec() { return audioCodec; }
        public long lqSize() { return lqSize; }
        public String hqPath() { return hqPath; }

        /** 旧构造入口（无 lqSize），保持向后兼容（lqSize=0、hqPath 传入）。 */
        public MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, String mediaType, Integer width, Integer height,
                         BigDecimal duration, String container, String videoCodec, String audioCodec,
                         String hqPath) {
            this(fileName, pageNumber, hqStatus, lqStatus, fileSize, mediaType, width, height,
                    duration, container, videoCodec, audioCodec, 0L, hqPath);
        }

        /** 旧构造入口（无 lqSize/hqPath），保持向后兼容。 */
        public MediaItem(String fileName, int pageNumber, String hqStatus, String lqStatus,
                         long fileSize, String mediaType, Integer width, Integer height,
                         BigDecimal duration, String container, String videoCodec, String audioCodec) {
            this(fileName, pageNumber, hqStatus, lqStatus, fileSize, mediaType, width, height,
                    duration, container, videoCodec, audioCodec, 0L, null);
        }
    }
}
