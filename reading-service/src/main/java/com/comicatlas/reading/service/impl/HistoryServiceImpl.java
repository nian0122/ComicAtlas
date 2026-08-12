package com.comicatlas.reading.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.contract.reader.dto.HistoryUpdateRequest;
import com.comicatlas.contract.reader.dto.HistoryVO;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryServiceImpl implements HistoryService {

    private final ReadingHistoryMapper historyMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final FileUrlResolver fileUrlResolver;

    @Override
    public List<HistoryVO> listHistory() {
        var histories = historyMapper.selectList(
            new LambdaQueryWrapper<ReadingHistory>()
                .orderByDesc(ReadingHistory::getUpdatedAt));
        if (histories.isEmpty()) {
            return List.of();
        }

        List<Long> comicIds = histories.stream()
                .map(ReadingHistory::getComicId).distinct().toList();
        Map<Long, Comic> comicMap = comicMapper.selectBatchIds(comicIds).stream()
                .collect(Collectors.toMap(Comic::getId, c -> c));

        List<Long> chapterIds = histories.stream()
                .map(ReadingHistory::getChapterId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Chapter> chapterMap = chapterIds.isEmpty()
                ? Map.of()
                : chapterMapper.selectBatchIds(chapterIds).stream()
                        .collect(Collectors.toMap(Chapter::getId, c -> c));

        return histories.stream()
                .map(h -> buildVO(h, comicMap, chapterMap))
                .toList();
    }

    @Override
    public HistoryVO getHistory(Long comicId) {
        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId));
        if (history == null) {
            return null;
        }
        Comic comic = comicMapper.selectById(history.getComicId());
        Chapter chapter = history.getChapterId() != null
                ? chapterMapper.selectById(history.getChapterId())
                : null;
        return buildVO(history,
                comic != null ? Map.of(comic.getId(), comic) : Map.of(),
                chapter != null ? Map.of(chapter.getId(), chapter) : Map.of());
    }

    @Override
    public void upsertHistory(Long comicId, HistoryUpdateRequest request) {
        var existing = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId));
        if (existing != null) {
            existing.setChapterId(request.getChapterId());
            existing.setPageNumber(request.getPageNumber());
            existing.setUpdatedAt(LocalDateTime.now());
            historyMapper.updateById(existing);
        } else {
            ReadingHistory history = new ReadingHistory();
            history.setComicId(comicId);
            history.setChapterId(request.getChapterId());
            history.setPageNumber(request.getPageNumber());
            historyMapper.insert(history);
        }
    }

    private HistoryVO buildVO(ReadingHistory h, Map<Long, Comic> comicMap, Map<Long, Chapter> chapterMap) {
        HistoryVO vo = new HistoryVO();
        vo.setComicId(h.getComicId());
        vo.setChapterId(h.getChapterId());
        vo.setPageNumber(h.getPageNumber());
        vo.setUpdatedAt(h.getUpdatedAt());

        Comic comic = comicMap.get(h.getComicId());
        if (comic != null) {
            vo.setComicTitle(comic.getTitle());
            vo.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
            if (comic.getTotalPages() != null && comic.getTotalPages() > 0) {
                vo.setTotalPages(comic.getTotalPages());
                if (h.getPageNumber() != null) {
                    vo.setProgressPercent(h.getPageNumber() * 100 / comic.getTotalPages());
                }
            }
        }

        Chapter chapter = h.getChapterId() != null ? chapterMap.get(h.getChapterId()) : null;
        if (chapter != null) {
            vo.setChapterNo(chapter.getChapterNo());
        }

        return vo;
    }
}
