package com.comicatlas.api.comic.service.impl;

import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class ComicMetadataServiceTest {

    @Mock
    private ComicMapper comicMapper;

    @InjectMocks
    private ComicServiceImpl service;

    @Test
    void getMetadata_shouldReturnDto_whenComicExists() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setTitle("Test Title");
        comic.setAuthor("Test Author");
        comic.setDescription("Test Description");
        when(comicMapper.selectById(1L)).thenReturn(comic);

        ComicMetadataDTO result = service.getMetadata(1L);

        assertEquals("Test Title", result.getTitle());
        assertEquals("Test Author", result.getAuthor());
        assertEquals("Test Description", result.getDescription());
        verify(comicMapper).selectById(1L);
    }

    @Test
    void getMetadata_shouldThrow404_whenComicNotFound() {
        when(comicMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMetadata(99L));
        assertEquals(404, ex.getCode());
        assertEquals("漫画不存在", ex.getMessage());
    }
}
