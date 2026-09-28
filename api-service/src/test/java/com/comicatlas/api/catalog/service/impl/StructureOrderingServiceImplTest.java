package com.comicatlas.api.catalog.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.dto.StructureOrderRequest;
import com.comicatlas.api.catalog.enums.StructureNodeType;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ChapterOrderUpdate;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructureOrderingServiceImplTest {

    @Test
    @DisplayName("同级章节按统一顺序更新 sort_order 并派生 global_order")
    void reorderSiblings_updatesLocalAndGlobalOrders() {
        CatalogMapper catalogMapper = mock(CatalogMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        ComicMapper comicMapper = mock(ComicMapper.class);
        when(comicMapper.selectById(342L)).thenReturn(new Comic());
        when(catalogMapper.selectAllByComicIdForUpdate(342L)).thenReturn(List.of());
        when(chapterMapper.selectByComicIdOrderByGlobalOrderForUpdate(342L))
                .thenReturn(List.of(chapter(10L, 1), chapter(20L, 2)));
        when(chapterMapper.updateOrdersBatch(eq(342L), anyList())).thenReturn(2);

        StructureOrderingServiceImpl service = new StructureOrderingServiceImpl(
                catalogMapper, chapterMapper, comicMapper, mock(CatalogCacheInvalidator.class));
        StructureOrderRequest request = new StructureOrderRequest();
        request.setItems(List.of(item(20L), item(10L)));

        service.reorder(342L, request);

        ArgumentCaptor<List<ChapterOrderUpdate>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chapterMapper).updateGlobalOrderToTemporaryNegative(342L);
        verify(chapterMapper).updateOrdersBatch(eq(342L), updatesCaptor.capture());
        assertThat(updatesCaptor.getValue())
                .containsExactly(new ChapterOrderUpdate(20L, 1, 1), new ChapterOrderUpdate(10L, 2, 2));
    }

    private Chapter chapter(Long chapterId, int globalOrder) {
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setComicId(342L);
        chapter.setCatalogId(null);
        chapter.setSortOrder(globalOrder);
        chapter.setGlobalOrder(globalOrder);
        chapter.setStatus(ChapterLifecycleStatus.READY);
        return chapter;
    }

    private StructureOrderRequest.StructureOrderItem item(Long chapterId) {
        StructureOrderRequest.StructureOrderItem orderItem = new StructureOrderRequest.StructureOrderItem();
        orderItem.setType(StructureNodeType.CHAPTER);
        orderItem.setId(chapterId);
        return orderItem;
    }
}
