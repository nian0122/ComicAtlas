package com.comicatlas.api.catalog.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 管理域目录与媒体查询服务契约。 */
public interface ManagementStructureQueryService {
    List<CatalogNode> tree(Long comicId);
    ReaderData chapter(Long chapterId);

   @lombok.Getter

    class ChapterRef {
        private final Long id;
        private final String chapterNo;
        private final String title;
        private final Integer globalOrder;
        private final Integer pageCount;
        private final String status;
        public ChapterRef(Long id, String chapterNo, String title, Integer globalOrder, Integer pageCount, String status) {
            this.id = id;
            this.chapterNo = chapterNo;
            this.title = title;
            this.globalOrder = globalOrder;
            this.pageCount = pageCount;
            this.status = status;
        }
        public Long id() { return id; }
        public String chapterNo() { return chapterNo; }
        public String title() { return title; }
        public Integer globalOrder() { return globalOrder; }
        public Integer pageCount() { return pageCount; }
        public String status() { return status; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ChapterRef)) { return false; }
            ChapterRef that = (ChapterRef) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(chapterNo, that.chapterNo) && java.util.Objects.equals(title, that.title) && java.util.Objects.equals(globalOrder, that.globalOrder) && java.util.Objects.equals(pageCount, that.pageCount) && java.util.Objects.equals(status, that.status);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(id, chapterNo, title, globalOrder, pageCount, status); }
        @Override
        public String toString() { return "ChapterRef[" + "id=" + id + ", " + "chapterNo=" + chapterNo + ", " + "title=" + title + ", " + "globalOrder=" + globalOrder + ", " + "pageCount=" + pageCount + ", " + "status=" + status + "]"; } }

    @lombok.Data
   @lombok.Getter
    class CatalogNode {
        private Long id;
        private String title;
        private List<CatalogNode> children = new ArrayList<>();
        private List<ChapterRef> chapters = new ArrayList<>();
        public CatalogNode(Long id, String title) { this.id = id; this.title = title; }
    }

    @lombok.Data
   @lombok.Getter
    class ReaderData {
        private Long chapterId;
        private Long comicId;
        private String chapterTitle;
        private List<MediaData> pages;
        private int total;
    }

    @lombok.Data
   @lombok.Getter
    class MediaData {
        private Long id;
        private Integer pageNumber;
        private String fileName;
        private String hqUrl;
        private String hqStatus;
        private String lqUrl;
        private String lqStatus;
        private Integer width;
        private Integer height;
        private Long hqSize;
        private Long lqSize;
        private String transcodeStatus;
        private String mediaType;
        private BigDecimal duration;
        private String container;
        private String videoCodec;
        private String audioCodec;
    }
}
