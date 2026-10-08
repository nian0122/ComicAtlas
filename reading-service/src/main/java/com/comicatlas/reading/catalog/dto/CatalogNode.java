package com.comicatlas.reading.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/** 目录树节点（阅读端） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CatalogNode {
    private Long id;
    private String title;
    private List<CatalogNode> children;
    private List<ChapterRef> chapters;
    /** 目录在同级目录中的持久显示顺序。 */
    private Integer sortOrder;
    /** 目录在全书阅读顺序中的锚点（= 其下最小章节 globalOrder），兼容旧客户端。 */
    private Integer globalOrder;

    public CatalogNode(Long id, String title) {
        this(id, title, new ArrayList<>(), new ArrayList<>(), null, null);
    }

    public CatalogNode(Long id, String title, List<CatalogNode> children, List<ChapterRef> chapters) {
        this(id, title, children, chapters, null, null);
    }

    public CatalogNode(Long id, String title, List<CatalogNode> children, List<ChapterRef> chapters,
                       Integer globalOrder) {
        this(id, title, children, chapters, null, globalOrder);
    }
}
