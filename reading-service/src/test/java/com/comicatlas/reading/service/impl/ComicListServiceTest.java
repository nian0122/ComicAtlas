package com.comicatlas.reading.library.impl;

import com.comicatlas.reading.library.service.impl.ComicListQueryServiceImpl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.dto.ComicListVO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.reading.testutil.MybatisPlusLambdaCacheExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class ComicListServiceTest {

    @Mock
    private ComicMapper comicMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ReadingHistoryMapper historyMapper;
    @Mock
    private FileUrlResolver fileUrlResolver;

    @InjectMocks
    private ComicListQueryServiceImpl service;

    @Test
    void listComics_shouldBatchLoadRelations_whenPageContainsMultipleComics() {
        ComicListQuery query = new ComicListQuery();
        Comic firstComic = comic(1L, 10L, 100);
        Comic secondComic = comic(2L, 10L, 50);
        Page<Comic> comicPage = new Page<>(1, 20, 2);
        comicPage.setRecords(List.of(firstComic, secondComic));

        Category category = new Category();
        category.setId(10L);
        category.setName("冒险");

        ReadingHistory history = new ReadingHistory();
        history.setComicId(1L);
        history.setChapterId(101L);
        history.setPageNumber(25);

        when(comicMapper.selectPage(any(Page.class), same(query))).thenReturn(comicPage);
        when(categoryMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(category));
        when(historyMapper.selectList(any())).thenReturn(List.of(history));

        var result = service.listComics(query);

        List<ComicListVO> records = result.getRecords();
        assertEquals(2, records.size());
        assertEquals(1, result.getCurrent());
        assertEquals(20, result.getSize());
        assertEquals(2, result.getTotal());
        assertEquals("冒险", records.get(0).getCategoryName());
        assertEquals("冒险", records.get(1).getCategoryName());
        assertEquals(ComicStatus.READY, records.get(0).getStatus());
        assertEquals(101L, records.get(0).getLastReadChapterId());
        assertEquals(25, records.get(0).getProgressPercent());
        assertNull(records.get(1).getLastReadChapterId());
        verify(categoryMapper).selectBatchIds(List.of(10L));
        verify(historyMapper).selectList(any());
        verify(categoryMapper, never()).selectById(any());
        verify(historyMapper, never()).selectOne(any());
    }

    @Test
    void listComics_shouldSkipRelationQueries_whenPageIsEmpty() {
        ComicListQuery query = new ComicListQuery();
        Page<Comic> comicPage = new Page<>(1, 20, 0);
        when(comicMapper.selectPage(any(Page.class), same(query))).thenReturn(comicPage);

        var result = service.listComics(query);

        assertEquals(0, result.getRecords().size());
        verifyNoInteractions(categoryMapper, historyMapper);
    }

    private Comic comic(Long id, Long categoryId, Integer totalPages) {
        Comic comic = new Comic();
        comic.setId(id);
        comic.setCategoryId(categoryId);
        comic.setTotalPages(totalPages);
        comic.setStatus(ComicStatus.READY);
        return comic;
    }
}
