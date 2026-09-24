package com.comicatlas.reading.library.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * 查询一页漫画并缓存纯数据 DTO。
     * <p>
     * 缓存的是 ComicListPage（records + 分页元数据），而非 MyBatis-Plus IPage，
     * 避免把分页对象内部执行状态序列化进 Redis。
     */
    @Cacheable(
        cacheNames = ComicReferenceCache.COMIC_LIST,
        key = "#root.target.cacheKey(#query)",
        unless = "#result == null || #result.getRecords().isEmpty()")
    public ComicListPage listComics(ComicListQuery query) {
        ComicListQueryNormalizer.normalize(query);
        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);
        long lastPage = result.getTotal() == 0
                ? 1
                : (result.getTotal() + query.getSize() - 1) / query.getSize();
        if (query.getPage() > lastPage) {
            query.setPage((int) lastPage);
            page = new Page<>(lastPage, query.getSize());
            result = comicMapper.selectPage(page, query);
        }
        List<Comic> comics = result.getRecords();
        if (comics.isEmpty()) {
            IPage<ComicListVO> emptyPage = result.convert(comic ->
                    toListVO(comic, new HashMap<>(), new HashMap<>()));
            return ComicListPage.of(emptyPage.getRecords(), emptyPage.getTotal(), emptyPage.getCurrent(), emptyPage.getSize());
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

    /**
     * 生成查询缓存键：规范化全部查询条件后取 MD5 摘要，避免超长 key。
     * 同条件同键、不同条件不同键；listComics 的 @Cacheable 引用此方法。
     */
    public String cacheKey(ComicListQuery query) {
        String cacheKeySource = "v5|" + String.join("|",
                normalizeCacheValue(query.getKeyword()),
                normalizeCacheValue(query.getTag()),
                query.getTags() == null ? "" : String.join(",", query.getTags()),
                normalizeCacheValue(query.getTagMode()),
                normalizeCacheValue(query.getStatus()),
                normalizeCacheValue(query.getCategory()),
                normalizeCacheValue(query.getSourceType()),
                normalizeCacheValue(query.getSort()),
                normalizeCacheValue(query.getOrder()),
                String.valueOf(query.getPage()),
                String.valueOf(query.getSize()));
        return calculateMd5(cacheKeySource);
    }

    private static String normalizeCacheValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String calculateMd5(String cacheKeySource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(cacheKeySource.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexBuilder = new StringBuilder(bytes.length * 2);
            for (byte digestByte : bytes) {
                hexBuilder.append(Character.forDigit((digestByte >> 4) & 0xF, 16));
                hexBuilder.append(Character.forDigit(digestByte & 0xF, 16));
            }
            return hexBuilder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 不可用", exception);
        }
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
