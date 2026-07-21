package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MediaItemInfo {
    private Long id;
    private Integer pageNumber;
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
