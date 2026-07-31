package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.service.ComicListQueryService;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);
        List<Comic> comics = result.getRecords();
        if (comics.isEmpty()) {
            return result.convert(comic -> toListVO(comic, Map.of(), Map.of()));
        }

        List<Long> categoryIds = comics.stream()
                .map(Comic::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> categoryNames = categoryIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));

        List<Long> comicIds = comics.stream().map(Comic::getId).toList();
        Map<Long, ReadingHistory> histories = historyMapper.selectList(
                        new LambdaQueryWrapper<ReadingHistory>().in(ReadingHistory::getComicId, comicIds))
                .stream()
                .collect(Collectors.toMap(ReadingHistory::getComicId, history -> history));

        return result.convert(comic -> toListVO(comic, categoryNames, histories));
    }

    private ComicListVO toListVO(
            Comic comic,
            Map<Long, String> categoryNames,
            Map<Long, ReadingHistory> histories) {
        ComicListVO vo = new ComicListVO();
        vo.setId(comic.getId());
        vo.setTitle(comic.getTitle());
        vo.setAuthor(comic.getAuthor());
        vo.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
        vo.setPageCount(comic.getTotalPages());
        vo.setCategoryId(comic.getCategoryId());
        vo.setCategoryName(categoryNames.get(comic.getCategoryId()));
        vo.setStatus(comic.getStatus());
        vo.setCreatedAt(comic.getCreatedAt());

        ReadingHistory history = histories.get(comic.getId());
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / comic.getTotalPages());
        }
        return vo;
    }
}
