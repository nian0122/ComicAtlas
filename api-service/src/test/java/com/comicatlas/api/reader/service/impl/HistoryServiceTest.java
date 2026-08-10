package com.comicatlas.api.reader.service.impl;

import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.reader.dto.HistoryUpdateRequest;
import com.comicatlas.api.reader.dto.HistoryVO;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.reader.service.HistoryService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        when(comicMapper.selectBatchIds(List.of(10L, 20L))).thenReturn(List.of(comic1, comic2));

        Chapter ch1 = chapter(100L, "1");
        Chapter ch2 = chapter(200L, "2");
        when(chapterMapper.selectBatchIds(List.of(100L, 200L))).thenReturn(List.of(ch1, ch2));

        List<HistoryVO> result = service.listHistory();

        assertEquals(2, result.size());
        assertEquals("火影", result.get(0).getComicTitle());
        assertEquals("1", result.get(0).getChapterNo());
        assertEquals("海贼", result.get(1).getComicTitle());
        assertEquals("2", result.get(1).getChapterNo());

        verify(comicMapper).selectBatchIds(eq(List.of(10L, 20L)));
        verify(comicMapper, never()).selectById(any());
        verify(chapterMapper).selectBatchIds(eq(List.of(100L, 200L)));
        verify(chapterMapper, never()).selectById(any());
    }

    @Test
    void listHistory_shouldHandleMissingComicOrChapter() {
        ReadingHistory h = history(1L, 99L, 999L);
        when(historyMapper.selectList(any())).thenReturn(List.of(h));
        when(comicMapper.selectBatchIds(List.of(99L))).thenReturn(List.of());
        when(chapterMapper.selectBatchIds(List.of(999L))).thenReturn(List.of());

        List<HistoryVO> result = service.listHistory();

        assertEquals(1, result.size());
        assertNull(result.get(0).getComicTitle());
        assertNull(result.get(0).getChapterNo());
    }

    @Test
    void listHistory_shouldSkipChapterBatch_whenNoChapterIds() {
        ReadingHistory h = history(1L, 10L, null);
        when(historyMapper.selectList(any())).thenReturn(List.of(h));
        when(comicMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(comic(10L, "火影")));

        List<HistoryVO> result = service.listHistory();

        assertEquals(1, result.size());
        assertEquals("火影", result.get(0).getComicTitle());
        verify(chapterMapper, never()).selectBatchIds(any());
    }

    @Test
    void upsertHistory_shouldRejectChapterFromAnotherComic() {
        Comic comic = comic(10L, "火影");
        Chapter chapter = chapter(200L, "1");
        chapter.setComicId(20L);
        HistoryUpdateRequest request = historyRequest(200L, 1);
        when(comicMapper.selectById(10L)).thenReturn(comic);
        when(chapterMapper.selectById(200L)).thenReturn(chapter);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upsertHistory(10L, request));

        assertEquals(HttpStatusCodes.CONFLICT, exception.getCode());
        verify(historyMapper, never()).selectOne(any());
        verify(historyMapper, never()).insert(any(ReadingHistory.class));
        verify(historyMapper, never()).updateById(any(ReadingHistory.class));
    }

    @Test
    void upsertHistory_shouldInsertWhenChapterBelongsToComic() {
        Comic comic = comic(10L, "火影");
        Chapter chapter = chapter(100L, "1");
        chapter.setComicId(10L);
        HistoryUpdateRequest request = historyRequest(100L, 2);
        when(comicMapper.selectById(10L)).thenReturn(comic);
        when(chapterMapper.selectById(100L)).thenReturn(chapter);
        when(historyMapper.selectOne(any())).thenReturn(null);

        service.upsertHistory(10L, request);

        verify(historyMapper).insert(any(ReadingHistory.class));
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

    private static HistoryUpdateRequest historyRequest(Long chapterId, Integer pageNumber) {
        HistoryUpdateRequest request = new HistoryUpdateRequest();
        request.setChapterId(chapterId);
        request.setPageNumber(pageNumber);
        return request;
    }
}
