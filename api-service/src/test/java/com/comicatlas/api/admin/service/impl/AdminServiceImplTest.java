package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.recovery.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.RecoveryEngine;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.media.operation.MediaOperationCommandService;
import com.comicatlas.api.recovery.service.impl.RecoveryCompatibilityServiceImpl;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.io.Serializable;

import static org.mockito.ArgumentMatchers.any;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyLong;

@ExtendWith(MockitoExtension.class)
class RecoveryCompatibilityServiceImplTest {

    @Mock
    private RecoveryEngine recoveryEngine;
    @Mock
    private CatalogCacheInvalidator catalogCacheInvalidator;
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
    @Mock
    private MediaOperationCommandService mediaOperationCommandService;

    @InjectMocks
    private RecoveryCompatibilityServiceImpl service;

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
    void deleteComic_shouldRedirectToUnifiedTaskPipeline_whenSuccessful() {
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
        when(mediaMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(50L);
        when(catalogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(comicTagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        when(historyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(10L);
        when(mediaOperationCommandService.requestComicDelete(1L))
                .thenReturn(OperationSubmitResultDTO.of(9L, "COMIC_DELETE", "QUEUED", 1));

        ComicDeleteStatsDTO stats = service.deleteComic(1L, "DELETE_FILES");

        assertEquals(50, stats.getPage());
        assertEquals(2, stats.getChapter());
        assertEquals(3, stats.getCatalog());
        assertEquals(5, stats.getTag());
        assertEquals(10, stats.getHistory());
        assertEquals(1, stats.getComic());

        verify(mediaOperationCommandService).requestComicDelete(1L);
        // 不再先删 DB 后发 MQ
        verify(comicMapper, never()).deleteById(anyLong());
        verify(chapterMapper, never()).delete(any());
        verify(mediaMapper, never()).delete(any());
    }

    @Test
    void deleteComic_shouldHandleComicWithNoChapters() {
        Comic comic = new Comic();
        comic.setId(1L);
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(taskMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        when(catalogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
        when(comicTagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(historyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(mediaOperationCommandService.requestComicDelete(1L))
                .thenReturn(OperationSubmitResultDTO.of(10L, "COMIC_DELETE", "QUEUED", 1));

        ComicDeleteStatsDTO stats = service.deleteComic(1L, "DATABASE_ONLY");

        assertEquals(0, stats.getPage());
        assertEquals(0, stats.getChapter());
        assertEquals(2, stats.getCatalog());
        assertEquals(3, stats.getTag());
        assertEquals(1, stats.getHistory());
        assertEquals(1, stats.getComic());

        verify(mediaOperationCommandService).requestComicDelete(1L);
    }
}
