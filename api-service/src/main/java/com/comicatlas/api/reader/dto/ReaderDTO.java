package com.comicatlas.api.reader.dto;

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
        private String hqUrl;
        private String lqUrl;
        private String lqStatus;
        private Integer width;
        private Integer height;
        private String mediaType;
        private BigDecimal duration;
        private String container;
        private String videoCodec;
        private String audioCodec;
    }
}
