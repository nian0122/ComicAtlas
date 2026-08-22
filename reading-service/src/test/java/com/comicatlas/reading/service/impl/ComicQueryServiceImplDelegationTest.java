package com.comicatlas.reading.library.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.reading.library.ComicListPage;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.ComicListVO;
import com.comicatlas.reading.library.ComicListQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicQueryServiceImplDelegationTest {

    @Mock
    private ComicListQueryService comicListQueryService;

    @InjectMocks
    private ComicQueryServiceImpl service;

    @Test
    void listComics_shouldLoadPageAndReturnAssembledIPage() {
        ComicListQuery query = new ComicListQuery();
        ComicListVO vo = new ComicListVO();
        vo.setId(1L);
        vo.setTitle("测试");

        ComicListPage page = new ComicListPage();
        page.setRecords(List.of(vo));
        page.setTotal(1);
        page.setCurrent(1);
        page.setSize(20);
        when(comicListQueryService.loadPage(query)).thenReturn(page);

        var result = service.listComics(query);

        // 走 loadPage（缓存方法）并组装为 IPage
        verify(comicListQueryService).loadPage(query);
        assertEquals(1, result.getRecords().size());
        assertEquals("测试", result.getRecords().get(0).getTitle());
        assertEquals(1L, result.getTotal());
    }
}
