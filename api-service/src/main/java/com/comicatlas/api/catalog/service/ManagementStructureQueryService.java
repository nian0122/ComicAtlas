package com.comicatlas.api.catalog.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 管理域目录与媒体查询服务契约。 */
public interface ManagementStructureQueryService {
    List<CatalogNode> tree(Long comicId);
    ReaderData chapter(Long chapterId);

    record ChapterRef(Long id, String chapterNo, String title, Integer globalOrder,
                      Integer pageCount, String status) { }

    @lombok.Data
    class CatalogNode {
        private Long id;
        private String title;
        private List<CatalogNode> children = new ArrayList<>();
        private List<ChapterRef> chapters = new ArrayList<>();
        public CatalogNode(Long id, String title) { this.id = id; this.title = title; }
    }

    @lombok.Data
    class ReaderData {
        private Long chapterId;
        private Long comicId;
        private String chapterTitle;
        private List<MediaData> pages;
        private int total;
    }

    @lombok.Data
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
