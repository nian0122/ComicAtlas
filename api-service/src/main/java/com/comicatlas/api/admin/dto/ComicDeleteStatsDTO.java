package com.comicatlas.api.admin.dto;

import lombok.Data;

@Data
public class ComicDeleteStatsDTO {
    private int comic;
    private int catalog;
    private int chapter;
    private int page;
    private int tag;
    private int history;
}
