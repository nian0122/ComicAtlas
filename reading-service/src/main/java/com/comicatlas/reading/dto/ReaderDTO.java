package com.comicatlas.reading.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 章节阅读数据（阅读域）。
 * <p>
 * 页面列表 + 前/后章节导航，供阅读器渲染；页面 URL 由 FileUrlResolver 统一生成。
 */
@Data
public class ReaderDTO {
    private Long chapterId;
    private Long comicId;
    private String chapterTitle;
    private List<MediaItemDTO> pages;
    private int total;
    private Long prevChapterId;
    private Long nextChapterId;

    /** 阅读页面条目（图片/视频混排） */
    @Data
    public static class MediaItemDTO {
        private Long id;
        private int pageNumber;
        /** 存储文件名 */
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
