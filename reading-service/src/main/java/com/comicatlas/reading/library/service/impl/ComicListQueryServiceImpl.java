package com.comicatlas.reading.library.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.reading.library.dto.ComicListPage;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.dto.ComicListVO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.reading.library.service.ComicListQueryService;
import com.comicatlas.reading.library.support.ComicListQueryNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ComicListQueryServiceImpl implements ComicListQueryService {

    private final ComicMapper comicMapper;
    private final CategoryMapper categoryMapper;
    private final ReadingHistoryMapper historyMapper;
    private final FileUrlResolver fileUrlResolver;

    /** 查询一页漫画并组装为阅读端分页 DTO。 */
    public ComicListPage listComics(ComicListQuery query) {
        ComicListQueryNormalizer.normalize(query);
        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);
        List<Comic> comics = result.getRecords();
        if (comics.isEmpty()) {
            return ComicListPage.of(List.of(), result.getTotal(), result.getCurrent(), result.getSize());
        }

        List<Long> categoryIds = comics.stream()
                .map(Comic::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty()
                ? new HashMap<>()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));

        List<Long> comicIds = comics.stream().map(Comic::getId).toList();
        Map<Long, ReadingHistory> histories = historyMapper.selectByComicIds(comicIds)
                .stream()
                .collect(Collectors.toMap(ReadingHistory::getComicId, readingHistory -> readingHistory));

        IPage<ComicListVO> comicListPage = result.convert(
                comic -> toListVO(comic, categoryNames, histories));
        return ComicListPage.of(comicListPage.getRecords(), comicListPage.getTotal(),
                comicListPage.getCurrent(), comicListPage.getSize());
    }

    private ComicListVO toListVO(
            Comic comic,
            Map<Long, String> categoryNames,
            Map<Long, ReadingHistory> histories) {
        ComicListVO comicListView = new ComicListVO();
        comicListView.setId(comic.getId());
        comicListView.setTitle(comic.getTitle());
        comicListView.setAuthor(comic.getAuthor());
        comicListView.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
        comicListView.setPageCount(comic.getTotalPages());
        comicListView.setCategoryId(comic.getCategoryId());
        comicListView.setCategoryName(categoryNames.get(comic.getCategoryId()));
        comicListView.setStatus(toStatus(comic.getStatus() == null ? null : comic.getStatus().name()));
        comicListView.setCreatedAt(comic.getCreatedAt());

        ReadingHistory history = histories.get(comic.getId());
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            comicListView.setLastReadChapterId(history.getChapterId());
            comicListView.setLastReadPage(history.getPageNumber());
            comicListView.setProgressPercent(history.getPageNumber() * 100 / comic.getTotalPages());
        }
        return comicListView;
    }

    private static ComicStatus toStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ComicStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
