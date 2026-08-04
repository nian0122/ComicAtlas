package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicListServiceTest {

    @Mock
    private ComicMapper comicMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ReadingHistoryMapper historyMapper;
    @Mock
    private FileUrlResolver fileUrlResolver;
    @Mock
    private OperationPolicyService operationPolicyService;
    @Mock
    private ManagementTaskService managementTaskService;

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
        when(operationPolicyService.forComic(any())).thenReturn(AllowedOperations.of(Set.of(), Map.of()));
        when(managementTaskService.findActiveTasksForComics(List.of(1L, 2L))).thenReturn(Map.of());

        var result = service.listComics(query);

        List<ComicListVO> records = result.getRecords();
        assertEquals(2, records.size());
        assertEquals(1, result.getCurrent());
        assertEquals(20, result.getSize());
        assertEquals(2, result.getTotal());
        assertEquals("冒险", records.get(0).getCategoryName());
        assertEquals("冒险", records.get(1).getCategoryName());
        assertEquals(101L, records.get(0).getLastReadChapterId());
        assertEquals(25, records.get(0).getProgressPercent());
        assertNull(records.get(1).getLastReadChapterId());
        verify(categoryMapper).selectBatchIds(List.of(10L));
        verify(historyMapper).selectList(any());
        verify(categoryMapper, never()).selectById(any());
        verify(historyMapper, never()).selectOne(any());
        verify(operationPolicyService, times(2)).forComic(any());
        verify(managementTaskService).findActiveTasksForComics(List.of(1L, 2L));
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
        return comic;
    }
}
