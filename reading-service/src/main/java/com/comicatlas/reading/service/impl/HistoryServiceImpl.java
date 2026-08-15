package com.comicatlas.reading.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.reading.dto.HistoryUpdateRequest;
import com.comicatlas.reading.dto.HistoryVO;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.reading.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryServiceImpl implements HistoryService {

    /** 进度百分比换算基数 */
    private static final int PERCENT_SCALE = 100;

    private final ReadingHistoryMapper readingHistoryMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final FileUrlResolver fileUrlResolver;

    @Override
    public List<HistoryVO> listHistory() {
        List<ReadingHistory> histories = readingHistoryMapper.selectList(
            new LambdaQueryWrapper<ReadingHistory>()
                .select(ReadingHistory::getComicId, ReadingHistory::getChapterId,
                        ReadingHistory::getPageNumber, ReadingHistory::getUpdatedAt)
                .orderByDesc(ReadingHistory::getUpdatedAt));
        if (histories.isEmpty()) {
            return List.of();
        }

        List<Long> comicIds = histories.stream()
                .map(ReadingHistory::getComicId).distinct().toList();
        Map<Long, Comic> comicMap = comicMapper.selectList(
                        new LambdaQueryWrapper<Comic>()
                            .select(Comic::getId, Comic::getTitle, Comic::getTotalPages)
                            .in(Comic::getId, comicIds))
                .stream()
                .collect(Collectors.toMap(Comic::getId, Function.identity(), (first, duplicate) -> first));

        List<Long> chapterIds = histories.stream()
                .map(ReadingHistory::getChapterId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Chapter> chapterMap = chapterIds.isEmpty()
                ? Map.of()
                : chapterMapper.selectList(
                        new LambdaQueryWrapper<Chapter>()
                            .select(Chapter::getId, Chapter::getChapterNo)
                            .in(Chapter::getId, chapterIds))
                    .stream()
                    .collect(Collectors.toMap(Chapter::getId, Function.identity(), (first, duplicate) -> first));

        return histories.stream()
                .map(history -> buildVO(history, comicMap, chapterMap))
                .toList();
    }

    @Override
    public HistoryVO getHistory(Long comicId) {
        ReadingHistory history = readingHistoryMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>()
                .select(ReadingHistory::getComicId, ReadingHistory::getChapterId,
                        ReadingHistory::getPageNumber, ReadingHistory::getUpdatedAt)
                .eq(ReadingHistory::getComicId, comicId));
        if (history == null) {
            return null;
        }
        Comic comic = comicMapper.selectOne(
            new LambdaQueryWrapper<Comic>()
                .select(Comic::getId, Comic::getTitle, Comic::getTotalPages)
                .eq(Comic::getId, history.getComicId()));
        Chapter chapter = history.getChapterId() != null
                ? chapterMapper.selectOne(
                        new LambdaQueryWrapper<Chapter>()
                            .select(Chapter::getId, Chapter::getChapterNo)
                            .eq(Chapter::getId, history.getChapterId()))
                : null;
        return buildVO(history,
                comic != null ? Map.of(comic.getId(), comic) : Map.of(),
                chapter != null ? Map.of(chapter.getId(), chapter) : Map.of());
    }

    @Override
    public void upsertHistory(Long comicId, HistoryUpdateRequest request) {
        ReadingHistory existing = readingHistoryMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>()
                .select(ReadingHistory::getId)
                .eq(ReadingHistory::getComicId, comicId));
        if (existing != null) {
            existing.setChapterId(request.getChapterId());
            existing.setPageNumber(request.getPageNumber());
            existing.setUpdatedAt(LocalDateTime.now());
            readingHistoryMapper.updateById(existing);
        } else {
            ReadingHistory history = new ReadingHistory();
            history.setComicId(comicId);
            history.setChapterId(request.getChapterId());
            history.setPageNumber(request.getPageNumber());
            readingHistoryMapper.insert(history);
        }
    }

    private HistoryVO buildVO(ReadingHistory history, Map<Long, Comic> comicMap, Map<Long, Chapter> chapterMap) {
        HistoryVO historyVO = new HistoryVO();
        historyVO.setComicId(history.getComicId());
        historyVO.setChapterId(history.getChapterId());
        historyVO.setPageNumber(history.getPageNumber());
        historyVO.setUpdatedAt(history.getUpdatedAt());

        Comic comic = comicMap.get(history.getComicId());
        if (comic != null) {
            historyVO.setComicTitle(comic.getTitle());
            historyVO.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
            if (comic.getTotalPages() != null && comic.getTotalPages() > 0) {
                historyVO.setTotalPages(comic.getTotalPages());
                if (history.getPageNumber() != null) {
                    historyVO.setProgressPercent(history.getPageNumber() * PERCENT_SCALE / comic.getTotalPages());
                }
            }
        }

        Chapter chapter = history.getChapterId() != null ? chapterMap.get(history.getChapterId()) : null;
        if (chapter != null) {
            historyVO.setChapterNo(chapter.getChapterNo());
        }

        return historyVO;
    }
}
