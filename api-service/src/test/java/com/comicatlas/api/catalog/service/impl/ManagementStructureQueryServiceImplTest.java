package com.comicatlas.api.catalog.service.impl;

import com.comicatlas.api.catalog.service.ManagementStructureQueryService.CatalogNode;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagementStructureQueryServiceImplTest {

    @Test
    @DisplayName("管理目录树按同级 sortOrder 排序并保留阅读锚点")
    void tree_sortsCatalogsBySortOrderAndSetsAnchors() {
        ComicMapper comicMapper = mock(ComicMapper.class);
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        when(comicMapper.selectById(342L)).thenReturn(new Comic());
        when(catalogMapper.selectByComicIdOrderBySortOrder(342L))
                .thenReturn(List.of(catalog(12L, "VOL 12", 1), catalog(3L, "VOL 3", 2)));
        when(chapterMapper.selectReadyCatalogChapters(342L))
                .thenReturn(List.of(chapter(1L, null, 1), chapter(12L, 12L, 12), chapter(3L, 3L, 3)));

        ManagementStructureQueryServiceImpl service = new ManagementStructureQueryServiceImpl(
                comicMapper, catalogMapper, chapterMapper, mock(MediaMapper.class), mock(FileUrlResolver.class));

        List<CatalogNode> tree = service.tree(342L);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getGlobalOrder()).isEqualTo(1);
        assertThat(tree.get(0).getChapters()).extracting("globalOrder").containsExactly(1);
        assertThat(tree.get(0).getChildren()).extracting(CatalogNode::getTitle)
                .containsExactly("VOL 12", "VOL 3");
        assertThat(tree.get(0).getChildren()).extracting(CatalogNode::getGlobalOrder)
                .containsExactly(12, 3);
        assertThat(tree.get(0).getChildren()).extracting(CatalogNode::getSortOrder)
                .containsExactly(1, 2);
    }

    private Catalog catalog(Long catalogId, String title, int sortOrder) {
        Catalog catalog = new Catalog();
        catalog.setId(catalogId);
        catalog.setComicId(342L);
        catalog.setTitle(title);
        catalog.setSortOrder(sortOrder);
        return catalog;
    }

    private Chapter chapter(Long chapterId, Long catalogId, int globalOrder) {
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setComicId(342L);
        chapter.setCatalogId(catalogId);
        chapter.setChapterNo(String.valueOf(chapterId));
        chapter.setTitle("章节 " + chapterId);
        chapter.setGlobalOrder(globalOrder);
        chapter.setPageCount(1);
        return chapter;
    }
}
