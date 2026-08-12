package com.comicatlas.contract.comic.dto;

import lombok.Data;

@Data
public class ComicMetadataDTO {
    private String title;
    private String author;
    private String description;
    private Long categoryId;
}