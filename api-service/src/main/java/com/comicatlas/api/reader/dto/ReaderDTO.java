package com.comicatlas.api.reader.dto;

import lombok.Data;
import java.util.List;

@Data
public class ReaderDTO {
    private Long chapterId;
    private String chapterTitle;
    private List<PageDTO> pages;
    private int total;
    private Long prevChapterId;
    private Long nextChapterId;

    @Data
    public static class PageDTO {
        private Long id;
        private int pageNumber;
        private String hqUrl;
        private String lqUrl;
        private String lqStatus;
        private Integer width;
        private Integer height;
    }
}
