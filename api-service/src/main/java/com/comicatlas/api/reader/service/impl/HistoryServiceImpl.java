package com.comicatlas.api.reader.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.reader.dto.*;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.reader.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryServiceImpl implements HistoryService {

    private final ReadingHistoryMapper historyMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;

    @Override
    public List<HistoryVO> listHistory() {
        var histories = historyMapper.selectList(
            new LambdaQueryWrapper<ReadingHistory>()
                .orderByDesc(ReadingHistory::getUpdatedAt));
        return histories.stream().map(this::buildVO).toList();
    }

    @Override
    public HistoryVO getHistory(Long comicId) {
        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getComicId, comicId));
        if (history == null) return null;
        return buildVO(history);
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
            ReadingHistory rh = new ReadingHistory();
            rh.setComicId(comicId);
            rh.setChapterId(request.getChapterId());
            rh.setPageNumber(request.getPageNumber());
            historyMapper.insert(rh);
        }
    }

    private HistoryVO buildVO(ReadingHistory h) {
        HistoryVO vo = new HistoryVO();
        vo.setComicId(h.getComicId());
        vo.setChapterId(h.getChapterId());
        vo.setPageNumber(h.getPageNumber());
        vo.setUpdatedAt(h.getUpdatedAt());

        Comic comic = comicMapper.selectById(h.getComicId());
        if (comic != null) {
            vo.setComicTitle(comic.getTitle());
            vo.setCoverUrl("/files/thumbs/" + comic.getId() + "/cover.webp");
            if (comic.getTotalPages() != null && comic.getTotalPages() > 0) {
                vo.setTotalPages(comic.getTotalPages());
                if (h.getPageNumber() != null) {
                    vo.setProgressPercent(h.getPageNumber() * 100 / comic.getTotalPages());
                }
            }
        }

        Chapter chapter = chapterMapper.selectById(h.getChapterId());
        if (chapter != null) {
            vo.setChapterNo(chapter.getChapterNo());
        }

        return vo;
    }
}
