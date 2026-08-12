package com.comicatlas.contract.comic.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComicTagUpdateDTO {
    private List<Long> tagIds;
}
