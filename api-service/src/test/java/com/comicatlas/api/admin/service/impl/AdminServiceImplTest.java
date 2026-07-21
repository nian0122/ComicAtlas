package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ComicMapper comicMapper;
    @Mock
    private CatalogMapper catalogMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private MediaMapper mediaMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ComicTagMapper comicTagMapper;
    @Mock
    private ReadingHistoryMapper historyMapper;
    @Mock
    private ImportTaskMapper taskMapper;

    @InjectMocks
    private AdminServiceImpl service;

    @Test
    void deleteComic_shouldThrow400_whenModeIsInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteComic(1L, "FULL_DELETE"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("DATABASE_ONLY"));
    }

    @Test
    void deleteComic_shouldThrow400_whenModeIsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteComic(1L, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    void deleteComic_shouldThrow404_whenComicNotFound() {
        when(comicMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteComic(1L, "DATABASE_ONLY"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void deleteComic_shouldThrow409_whenRunningTaskExists() {
        Comic comic = new Comic();
        comic.setId(1L);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteComic(1L, "DATABASE_ONLY"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("运行中的导入任务"));
    }

    @Test
    void deleteComic_shouldReturnStats_whenSuccessful() {
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setTitle("Test Comic");
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        Chapter ch1 = new Chapter();
        ch1.setId(101L);
        Chapter ch2 = new Chapter();
        ch2.setId(102L);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ch1, ch2));

        when(mediaMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(50);
        when(chapterMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);
        when(catalogMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(3);
        when(comicTagMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(5);
        when(historyMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(10);
        when(comicMapper.deleteById(1L)).thenReturn(1);

        ComicDeleteStats stats = service.deleteComic(1L, "DATABASE_ONLY");

        assertEquals(50, stats.getPage());
        assertEquals(2, stats.getChapter());
        assertEquals(3, stats.getCatalog());
        assertEquals(5, stats.getTag());
        assertEquals(10, stats.getHistory());
        assertEquals(1, stats.getComic());

        verify(taskMapper, never()).delete(any());
    }

    @Test
    void deleteComic_shouldHandleComicWithNoChapters() {
        Comic comic = new Comic();
        comic.setId(1L);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        when(chapterMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(catalogMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);
        when(comicTagMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(3);
        when(historyMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(comicMapper.deleteById(1L)).thenReturn(1);

        ComicDeleteStats stats = service.deleteComic(1L, "DATABASE_ONLY");

        assertEquals(0, stats.getPage());
        assertEquals(0, stats.getChapter());
        assertEquals(2, stats.getCatalog());
        assertEquals(3, stats.getTag());
        assertEquals(1, stats.getHistory());
        assertEquals(1, stats.getComic());
    }

    @Test
    void deleteComic_shouldHandleComicWithNoTagsOrHistory() {
        Comic comic = new Comic();
        comic.setId(1L);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        Chapter ch = new Chapter();
        ch.setId(201L);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ch));

        when(mediaMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(10);
        when(chapterMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(catalogMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(comicTagMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(historyMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(comicMapper.deleteById(1L)).thenReturn(1);

        ComicDeleteStats stats = service.deleteComic(1L, "DATABASE_ONLY");

        assertEquals(10, stats.getPage());
        assertEquals(1, stats.getChapter());
        assertEquals(1, stats.getCatalog());
        assertEquals(0, stats.getTag());
        assertEquals(0, stats.getHistory());
        assertEquals(1, stats.getComic());
    }

    @Test
    void deleteComic_shouldProceed_whenTasksAreInTerminalStatus() {
        Comic comic = new Comic();
        comic.setId(1L);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(chapterMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(catalogMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(comicTagMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(historyMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(0);
        when(comicMapper.deleteById(1L)).thenReturn(1);

        ComicDeleteStats stats = service.deleteComic(1L, "DATABASE_ONLY");
        assertEquals(1, stats.getComic());
    }
}
