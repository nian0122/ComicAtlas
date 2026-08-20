package com.comicatlas.api.comic.dto;

import com.comicatlas.contract.common.enums.ComicStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端漫画列表视图，避免管理端依赖阅读服务 DTO。 */
@Data
public class ManagementComicListVO {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private Integer pageCount;
    private Long categoryId;
    private String categoryName;
    private ComicStatus status;
    private LocalDateTime createdAt;
}
