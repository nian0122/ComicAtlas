package com.comicatlas.reading.favorites.persistence;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 喜欢查询的内部投影，存储路径仅由服务交给 URL 解析器。 */
@Data
public class FavoriteRow {
    private Long id;
    private Long comicId;
    private String comicTitle;
    private Long chapterId;
    private String title;
    private Integer pageNumber;
    private String mediaType;
    private BigDecimal duration;
    private String previewRoot;
    private String previewPath;
    private LocalDateTime reactionAt;
    private Long lastReadChapterId;
    private Integer lastReadPageNumber;
}
