package com.comicatlas.reading.library.impl;

import com.comicatlas.reading.history.service.impl.HistoryServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.reading.history.dto.HistoryUpdateRequest;
import com.comicatlas.reading.history.dto.HistoryVO;
import com.comicatlas.reading.history.dto.HistoryPageVO;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.reading.history.service.HistoryService;
import com.comicatlas.reading.testutil.MybatisPlusLambdaCacheExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class HistoryServiceTest {

    @Mock
    private ReadingHistoryMapper historyMapper;
    @Mock
    private ComicMapper comicMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private FileUrlResolver fileUrlResolver;

    @InjectMocks
    private HistoryServiceImpl service;

    @Test
    void listHistory_shouldBatchLoadComicsAndChapters_notPerRow() {
        ReadingHistory h1 = history(1L, 10L, 100L);
        ReadingHistory h2 = history(2L, 20L, 200L);
        when(historyMapper.selectList(any())).thenReturn(List.of(h1, h2));

        Comic comic1 = comic(10L, "火影");
        Comic comic2 = comic(20L, "海贼");
        when(comicMapper.selectList(any())).thenReturn(List.of(comic1, comic2));

        Chapter ch1 = chapter(100L, "1");
        Chapter ch2 = chapter(200L, "2");
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1, ch2));

        List<HistoryVO> result = service.listHistory();

        assertEquals(2, result.size());
        assertEquals("火影", result.get(0).getComicTitle());
        assertEquals("1", result.get(0).getChapterNo());
        assertEquals("海贼", result.get(1).getComicTitle());
        assertEquals("2", result.get(1).getChapterNo());

        verify(comicMapper).selectList(any());
        verify(comicMapper, never()).selectById(any());
        verify(chapterMapper).selectList(any());
        verify(chapterMapper, never()).selectById(any());
    }

    @Test
    void listHistory_shouldHandleMissingComicOrChapter() {
        ReadingHistory h = history(1L, 99L, 999L);
        when(historyMapper.selectList(any())).thenReturn(List.of(h));
        when(comicMapper.selectList(any())).thenReturn(List.of());
        when(chapterMapper.selectList(any())).thenReturn(List.of());

        List<HistoryVO> result = service.listHistory();

        assertEquals(1, result.size());
        assertNull(result.get(0).getComicTitle());
        assertNull(result.get(0).getChapterNo());
    }

    @Test
    void listHistory_shouldSkipChapterBatch_whenNoChapterIds() {
        ReadingHistory h = history(1L, 10L, null);
        when(historyMapper.selectList(any())).thenReturn(List.of(h));
        when(comicMapper.selectList(any())).thenReturn(List.of(comic(10L, "火影")));

        List<HistoryVO> result = service.listHistory();

        assertEquals(1, result.size());
        assertEquals("火影", result.get(0).getComicTitle());
        verify(chapterMapper, never()).selectList(any());
    }

    @Test
    void pageHistory_shouldReturnPagedRecordsAndMetadata() {
        ReadingHistory history = history(1L, 10L, 100L);
        Page<ReadingHistory> page = new Page<>(2, 20);
        page.setRecords(List.of(history));
        page.setTotal(21);
        when(historyMapper.selectPage(any(), any())).thenReturn(page);
        when(comicMapper.selectList(any())).thenReturn(List.of(comic(10L, "火影")));
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(100L, "1")));

        HistoryPageVO result = service.pageHistory(2, 20);

        assertEquals(1, result.getRecords().size());
        assertEquals(21, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals(20, result.getSize());
        assertEquals("火影", result.getRecords().get(0).getComicTitle());
    }

    @Test
    void historyVO_shouldCalculateProgressByCurrentChapterPageCount() {
        ReadingHistory history = history(1L, 10L, 100L);
        history.setPageNumber(8);
        when(historyMapper.selectList(any())).thenReturn(List.of(history));
        when(comicMapper.selectList(any())).thenReturn(List.of(comic(10L, "火影")));
        Chapter chapter = chapter(100L, "1");
        chapter.setPageCount(20);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));

        HistoryVO result = service.listHistory().get(0);

        assertEquals(20, result.getTotalPages());
        assertEquals(40, result.getProgressPercent());
    }

    @Test
    void upsertHistory_shouldValidateOwnershipAndUseAtomicMapperUpsert() {
        Comic comic = comic(10L, "火影");
        comic.setStatus(ComicStatus.READY);
        Chapter chapter = chapter(100L, "1");
        chapter.setComicId(10L);
        chapter.setPageCount(20);
        chapter.setStatus(ChapterLifecycleStatus.READY);
        when(comicMapper.selectOne(any())).thenReturn(comic);
        when(chapterMapper.selectOne(any())).thenReturn(chapter);

        HistoryUpdateRequest request = new HistoryUpdateRequest();
        request.setChapterId(100L);
        request.setPageNumber(8);

        service.upsertHistory(10L, request);

        verify(historyMapper).upsert(any(ReadingHistory.class));
    }

    @Test
    void upsertHistory_shouldRejectPageOutsideChapter() {
        Comic comic = comic(10L, "火影");
        comic.setStatus(ComicStatus.READY);
        Chapter chapter = chapter(100L, "1");
        chapter.setComicId(10L);
        chapter.setPageCount(20);
        chapter.setStatus(ChapterLifecycleStatus.READY);
        when(comicMapper.selectOne(any())).thenReturn(comic);
        when(chapterMapper.selectOne(any())).thenReturn(chapter);

        HistoryUpdateRequest request = new HistoryUpdateRequest();
        request.setChapterId(100L);
        request.setPageNumber(21);

        assertThrows(BusinessException.class, () -> service.upsertHistory(10L, request));
        verify(historyMapper, never()).upsert(any(ReadingHistory.class));
    }

    @Test
    void upsertHistory_shouldRejectChapterFromAnotherComic() {
        Comic comic = comic(10L, "火影");
        comic.setStatus(ComicStatus.READY);
        Chapter chapter = chapter(100L, "1");
        chapter.setComicId(11L);
        chapter.setPageCount(20);
        chapter.setStatus(ChapterLifecycleStatus.READY);
        when(comicMapper.selectOne(any())).thenReturn(comic);
        when(chapterMapper.selectOne(any())).thenReturn(chapter);

        HistoryUpdateRequest request = new HistoryUpdateRequest();
        request.setChapterId(100L);
        request.setPageNumber(8);

        assertThrows(BusinessException.class, () -> service.upsertHistory(10L, request));
        verify(historyMapper, never()).upsert(any(ReadingHistory.class));
    }

    private static ReadingHistory history(Long id, Long comicId, Long chapterId) {
        ReadingHistory h = new ReadingHistory();
        h.setId(id);
        h.setComicId(comicId);
        h.setChapterId(chapterId);
        h.setPageNumber(1);
        h.setUpdatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        return h;
    }

    private static Comic comic(Long id, String title) {
        Comic c = new Comic();
        c.setId(id);
        c.setTitle(title);
        c.setTotalPages(100);
        return c;
    }

    private static Chapter chapter(Long id, String chapterNo) {
        Chapter ch = new Chapter();
        ch.setId(id);
        ch.setChapterNo(chapterNo);
        return ch;
    }
}
