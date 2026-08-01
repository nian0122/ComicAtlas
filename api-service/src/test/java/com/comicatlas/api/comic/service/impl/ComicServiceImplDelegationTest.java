package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.service.ComicListQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicServiceImplDelegationTest {

    @Mock
    private ComicListQueryService comicListQueryService;

    @InjectMocks
    private ComicServiceImpl service;

    @Test
    void listComics_shouldDelegateQueryAndReturnSamePage() {
        ComicListQuery query = new ComicListQuery();
        Page<ComicListVO> expected = new Page<>(1, 20, 0);
        when(comicListQueryService.listComics(query)).thenReturn(expected);

        var result = service.listComics(query);

        assertSame(expected, result);
        verify(comicListQueryService).listComics(query);
    }
}
