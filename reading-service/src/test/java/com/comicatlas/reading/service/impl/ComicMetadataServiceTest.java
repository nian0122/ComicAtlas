package com.comicatlas.reading.service.impl;

import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.reading.testutil.MybatisPlusLambdaCacheExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阅读端漫画元数据查询测试。
 * <p>
 * 元数据更新（updateMetadata）为管理操作，由管理服务 ComicManagementServiceImpl 覆盖。
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class ComicMetadataServiceTest {

    @Mock
    private ComicMapper comicMapper;

    @InjectMocks
    private ComicQueryServiceImpl service;

    @Test
    void getMetadata_shouldReturnDto_whenComicExists() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setTitle("Test Title");
        comic.setAuthor("Test Author");
        comic.setDescription("Test Description");
        when(comicMapper.selectOne(any())).thenReturn(comic);

        ComicMetadataDTO result = service.getMetadata(1L);

        assertEquals("Test Title", result.getTitle());
        assertEquals("Test Author", result.getAuthor());
        assertEquals("Test Description", result.getDescription());
        verify(comicMapper).selectOne(any());
    }

    @Test
    void getMetadata_shouldThrow404_whenComicNotFound() {
        when(comicMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMetadata(99L));
        assertEquals(404, ex.getCode());
        assertEquals("漫画不存在", ex.getMessage());
    }
}
