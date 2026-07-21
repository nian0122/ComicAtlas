package com.comicatlas.api.admin.dto;

import lombok.Data;

@Data
public class ComicStorageDTO {
    private Long comicId;
    private String title;
    private String coverUrl;
    private Long totalSize;
    private Long hqSize;
    private Long lqSize;
    private String hqStatus;
    private String lqStatus;
    private Integer chapterCount;
    private Integer pageCount;
}
