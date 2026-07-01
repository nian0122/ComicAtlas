package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class PageInfo {
    private Long id;
    private Integer pageNumber;
    private String hqUrl;
    private String lqUrl;
    private String lqStatus;
    private Integer width;
    private Integer height;
}
