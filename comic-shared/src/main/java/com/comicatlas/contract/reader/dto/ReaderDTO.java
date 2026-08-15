package com.comicatlas.contract.reader.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ReaderDTO {
    private Long chapterId;
    private Long comicId;
    private String chapterTitle;
    private List<MediaItemDTO> pages;
    private int total;
    private Long prevChapterId;
    private Long nextChapterId;

    @Data
    public static class MediaItemDTO {
        private Long id;
        private int pageNumber;
        /** HQ 存储文件名，仅用于管理端媒体维护展示。 */
        private String fileName;
        private String hqUrl;
        private String hqStatus;
        private String lqUrl;
        private String lqStatus;
        private Integer width;
        private Integer height;
        private Long fileSize;
        private Long lqSize;
        private String transcodeStatus;
        private String mediaType;
        private BigDecimal duration;
        private String container;
        private String videoCodec;
        private String audioCodec;
    }
}
