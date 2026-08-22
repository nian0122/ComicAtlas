package com.comicatlas.reading.library.impl;

import com.comicatlas.reading.catalog.impl.CatalogServiceImpl;
import com.comicatlas.reading.catalog.CatalogNode;
import com.comicatlas.reading.catalog.ChapterRef;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.reading.testutil.MybatisPlusLambdaCacheExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 目录树构建单元测试（TDD）。
 *
 * <p>覆盖 buildTree 的三种兼容形态（纯平铺/纯目录/根混合）、递归后序锚点、
 * 孤儿章节归根、确定性排序与 READY 状态过滤。
 */
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class CatalogServiceImplTest {

    private static final Long COMIC_ID = 10L;

    private final CatalogMapper catalogMapper = mock(CatalogMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final ComicMapper comicMapper = mock(ComicMapper.class);
    private final CatalogServiceImpl service = new CatalogServiceImpl(catalogMapper, chapterMapper, comicMapper);

    @BeforeEach
    void setUp() {
        reset(catalogMapper, chapterMapper, comicMapper);
        Comic comic = new Comic();
        comic.setId(COMIC_ID);
        comic.setStatus(ComicStatus.READY);
        when(comicMapper.selectOne(any())).thenReturn(comic);
    }

    private static Catalog cat(Long id, Long parentId, String title, int sortOrder) {
        Catalog c = new Catalog();
        c.setId(id);
        c.setComicId(COMIC_ID);
        c.setParentId(parentId);
        c.setTitle(title);
        c.setSortOrder(sortOrder);
        return c;
    }

    private static Chapter chapter(Long id, Long catalogId, int globalOrder, ChapterLifecycleStatus status) {
        Chapter ch = new Chapter();
        ch.setId(id);
        ch.setComicId(COMIC_ID);
        ch.setCatalogId(catalogId);
        ch.setChapterNo(String.valueOf(id));
        ch.setTitle("章节" + id);
        ch.setPageCount(1);
        ch.setGlobalOrder(globalOrder);
        ch.setStatus(status);
        return ch;
    }

    private void stubTree(List<Catalog> catalogs, List<Chapter> chapters) {
        when(catalogMapper.selectList(any())).thenReturn(catalogs);
        when(chapterMapper.selectList(any())).thenReturn(chapters);
    }

    private static List<Long> chapterIds(List<ChapterRef> refs) {
        return refs.stream().map(ChapterRef::getId).toList();
    }

    private static List<Long> nodeIds(List<CatalogNode> nodes) {
        return nodes.stream().map(CatalogNode::getId).toList();
    }

    @Test
    @DisplayName("纯平铺：无目录行 → 单个匿名根，chapters 为全部章节且按 globalOrder 排序")
    void buildTree_pureFlat_returnsAnonymousRootWithAllChapters() {
        stubTree(List.of(), List.of(
                chapter(1L, null, 5, ChapterLifecycleStatus.READY),
                chapter(2L, null, 3, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        assertEquals(1, roots.size());
        CatalogNode root = roots.get(0);
        assertNull(root.getId());
        assertNull(root.getTitle());
        assertTrue(root.getChildren().isEmpty());
        assertEquals(List.of(2L, 1L), chapterIds(root.getChapters()));
    }

    @Test
    @DisplayName("纯目录：无根级章节 → 直接返回顶层目录节点列表")
    void buildTree_pureCatalog_returnsTopLevelNodes() {
        stubTree(List.of(
                cat(1L, null, "卷A", 1),
                cat(2L, null, "卷B", 2)), List.of(
                chapter(1L, 1L, 1, ChapterLifecycleStatus.READY),
                chapter(2L, 2L, 2, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        assertEquals(List.of(1L, 2L), nodeIds(roots));
        assertEquals(List.of(1L), chapterIds(roots.get(0).getChapters()));
        assertEquals(List.of(2L), chapterIds(roots.get(1).getChapters()));
        assertEquals(1, roots.get(0).getGlobalOrder());
        assertEquals(2, roots.get(1).getGlobalOrder());
    }

    @Test
    @DisplayName("根混合：根级章节与顶层目录并存 → 单个匿名根，chapters=根级章节、children=顶层目录")
    void buildTree_mixed_returnsAnonymousRootWrappingRootChaptersAndTopCatalogs() {
        stubTree(List.of(
                cat(1L, null, "卷A", 1),
                cat(2L, null, "卷B", 2)), List.of(
                chapter(1L, null, 1, ChapterLifecycleStatus.READY),
                chapter(2L, 1L, 2, ChapterLifecycleStatus.READY),
                chapter(3L, 2L, 3, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        // 期望单个匿名根，根级章节不丢失
        assertEquals(1, roots.size());
        CatalogNode root = roots.get(0);
        assertNull(root.getId());
        assertNull(root.getTitle());
        assertEquals(List.of(1L), chapterIds(root.getChapters()));
        assertEquals(List.of(1L, 2L), nodeIds(root.getChildren()));
        assertEquals(List.of(2L), chapterIds(root.getChildren().get(0).getChapters()));
    }

    @Test
    @DisplayName("孤儿章节：catalogId 指向不存在的目录 → 归入匿名根 chapters")
    void buildTree_orphanChapter_goesToAnonymousRoot() {
        stubTree(List.of(
                cat(1L, null, "卷A", 1)), List.of(
                chapter(1L, 999L, 1, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        assertEquals(1, roots.size());
        CatalogNode root = roots.get(0);
        assertNull(root.getId());
        assertEquals(List.of(1L), chapterIds(root.getChapters()));
        assertEquals(List.of(1L), nodeIds(root.getChildren()));
    }

    @Test
    @DisplayName("三层嵌套：锚点 = 所有后代 READY 章节最小 globalOrder（递归后序）")
    void buildTree_threeLevelNesting_anchorIsMinOfAllDescendants() {
        stubTree(List.of(
                cat(1L, null, "卷A", 1),
                cat(2L, 1L, "卷A-子1", 1),
                cat(3L, 2L, "卷A-孙1", 1),
                cat(4L, null, "卷B", 2)), List.of(
                chapter(1L, 1L, 10, ChapterLifecycleStatus.READY),
                chapter(2L, 3L, 5, ChapterLifecycleStatus.READY),
                chapter(3L, 4L, 20, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        assertEquals(List.of(1L, 4L), nodeIds(roots));
        CatalogNode volA = roots.get(0);
        CatalogNode child = volA.getChildren().get(0);
        CatalogNode grandchild = child.getChildren().get(0);
        assertEquals(5, grandchild.getGlobalOrder());
        assertEquals(5, child.getGlobalOrder());
        assertEquals(5, volA.getGlobalOrder());
        assertEquals(20, roots.get(1).getGlobalOrder());
    }

    @Test
    @DisplayName("乱序 ID：锚点决定顶层顺序，空目录锚点 null 排最后")
    void buildTree_unorderedIds_sortedByAnchorThenStableId() {
        stubTree(List.of(
                cat(1L, null, "空目录", 3),
                cat(2L, null, "早目录", 1),
                cat(3L, null, "晚目录", 2)), List.of(
                chapter(1L, 2L, 1, ChapterLifecycleStatus.READY),
                chapter(2L, 3L, 2, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        assertEquals(List.of(2L, 3L, 1L), nodeIds(roots));
        assertEquals(1, roots.get(0).getGlobalOrder());
        assertEquals(2, roots.get(1).getGlobalOrder());
        assertNull(roots.get(2).getGlobalOrder());
    }

    @Test
    @DisplayName("空目录：锚点 null 排在有内容节点之后，不触发空值比较异常")
    void buildTree_emptyCatalog_anchorNullSortedAfter() {
        stubTree(List.of(
                cat(1L, null, "有内容", 1),
                cat(2L, null, "空目录", 2)), List.of(
                chapter(1L, 1L, 1, ChapterLifecycleStatus.READY)));

        List<CatalogNode> roots = service.buildTree(COMIC_ID);

        assertEquals(List.of(1L, 2L), nodeIds(roots));
        assertEquals(1, roots.get(0).getGlobalOrder());
        assertNull(roots.get(1).getGlobalOrder());
    }
}
