package com.comicatlas.api.catalog.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.dto.ChapterBatchReorderRequest;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.CatalogOrderUpdate;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ChapterOrderUpdate;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.trash.service.TrashLifecycleService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节管理服务乐观锁冲突单元测试。
 *
 * <p>确定性验证：当 updateById 受乐观锁版本条件影响而返回 0 行时，
 * 服务必须抛出 {@link ConflictException}（409），而不是静默成功。
 */
class ChapterManagementServiceImplTest {

    private ChapterManagementServiceImpl buildService(ChapterMapper chapterMapper) {
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        when(catalogMapper.selectByComicIdOrderBySortOrderForUpdate(any())).thenReturn(List.of());
        return new ChapterManagementServiceImpl(
                chapterMapper,
                catalogMapper,
                mock(ComicMapper.class),
                mock(CatalogCacheInvalidator.class),
                mock(TrashLifecycleService.class));
    }

    private ChapterManagementServiceImpl buildService(ChapterMapper chapterMapper, CatalogMapper catalogMapper) {
        return new ChapterManagementServiceImpl(
                chapterMapper,
                catalogMapper,
                mock(ComicMapper.class),
                mock(CatalogCacheInvalidator.class),
                mock(TrashLifecycleService.class));
    }

    @Test
    @DisplayName("updateById 返回 0 行（版本冲突）→ 抛 409 Conflict")
    void checkedUpdate_zeroRows_throwsConflict() {
        ChapterMapper mapper = mock(ChapterMapper.class);
        when(mapper.updateById(any(Chapter.class))).thenReturn(0);

        ChapterManagementServiceImpl service = buildService(mapper);
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setVersion(1);

        assertThatThrownBy(() -> service.checkedUpdate(chapter))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("并发");
    }

    @Test
    @DisplayName("updateById 返回 1 行 → 正常通过")
    void checkedUpdate_oneRow_passes() {
        ChapterMapper mapper = mock(ChapterMapper.class);
        when(mapper.updateById(any(Chapter.class))).thenReturn(1);

        ChapterManagementServiceImpl service = buildService(mapper);
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setVersion(1);

        service.checkedUpdate(chapter);
    }

    @Test
    @DisplayName("完整章节 ID 顺序一次性生成连续全局与目录顺序")
    void reorderChapters_updatesAllOrdersInOneBatch() {
        ChapterMapper mapper = mock(ChapterMapper.class);
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        Chapter firstChapter = chapter(10L, 1L);
        Chapter secondChapter = chapter(20L, 2L);
        Catalog firstCatalog = catalog(1L, 1);
        Catalog secondCatalog = catalog(2L, 2);
        when(mapper.selectByComicIdOrderByGlobalOrderForUpdate(342L))
                .thenReturn(List.of(firstChapter, secondChapter));
        when(mapper.updateOrdersBatch(eq(342L), anyList())).thenReturn(2);
        when(catalogMapper.selectByComicIdOrderBySortOrderForUpdate(342L))
                .thenReturn(List.of(firstCatalog, secondCatalog));
        when(catalogMapper.updateSortOrdersBatch(eq(342L), anyList())).thenReturn(2);

        ChapterBatchReorderRequest request = new ChapterBatchReorderRequest();
        request.setChapterIds(List.of(20L, 10L));
        ChapterManagementServiceImpl service = buildService(mapper, catalogMapper);

        service.reorderChapters(342L, request);

        ArgumentCaptor<List<ChapterOrderUpdate>> orderUpdatesCaptor = forClass(List.class);
        verify(mapper).updateGlobalOrderToTemporaryNegative(342L);
        verify(mapper).updateOrdersBatch(eq(342L), orderUpdatesCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(orderUpdatesCaptor.getValue())
                .containsExactly(new ChapterOrderUpdate(20L, 1, 1), new ChapterOrderUpdate(10L, 2, 1));
        ArgumentCaptor<List<CatalogOrderUpdate>> catalogOrderUpdatesCaptor = forClass(List.class);
        verify(catalogMapper).updateSortOrdersBatch(eq(342L), catalogOrderUpdatesCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(catalogOrderUpdatesCaptor.getValue())
                .containsExactly(new CatalogOrderUpdate(2L, 1), new CatalogOrderUpdate(1L, 2));
    }

    @Test
    @DisplayName("批量重排章节 ID 不完整或重复时拒绝更新")
    void reorderChapters_incompleteOrDuplicateIds_rejected() {
        ChapterMapper mapper = mock(ChapterMapper.class);
        when(mapper.selectByComicIdOrderByGlobalOrderForUpdate(342L))
                .thenReturn(List.of(chapter(10L, 1L), chapter(20L, 1L)));
        ChapterBatchReorderRequest request = new ChapterBatchReorderRequest();
        request.setChapterIds(List.of(10L, 10L));

        assertThatThrownBy(() -> buildService(mapper).reorderChapters(342L, request))
                .hasMessageContaining("完整且不能重复");
        verify(mapper, never()).updateGlobalOrderToTemporaryNegative(342L);
    }

    private Chapter chapter(Long chapterId, Long catalogId) {
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setComicId(342L);
        chapter.setCatalogId(catalogId);
        chapter.setGlobalOrder(chapterId.intValue());
        chapter.setSortOrder(1);
        chapter.setVersion(1);
        return chapter;
    }

    private Catalog catalog(Long catalogId, int sortOrder) {
        Catalog catalog = new Catalog();
        catalog.setId(catalogId);
        catalog.setComicId(342L);
        catalog.setSortOrder(sortOrder);
        return catalog;
    }
}
