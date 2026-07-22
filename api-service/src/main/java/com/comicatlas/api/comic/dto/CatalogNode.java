package com.comicatlas.api.comic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class CatalogNode {
    private Long id;
    private String title;
    private List<CatalogNode> children;
    private List<ChapterRef> chapters;

    public CatalogNode(Long id, String title) {
        this(id, title, new ArrayList<>(), new ArrayList<>());
    }
}
