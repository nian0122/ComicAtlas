package com.comicatlas.reading.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.cache.ComicReferenceCache;
import com.comicatlas.api.comic.dto.ComicListPage;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.reading.service.ComicListQueryService;
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

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        // 直接委托 loadPage（缓存方法）。注意：本方法内部调用不触发 @Cacheable（自调用绕过代理），
        // 缓存生效路径由 ComicQueryServiceImpl 通过代理调用 loadPage 触发。
        return loadPage(query).toPage();
    }

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
    public ComicListPage loadPage(ComicListQuery query) {
        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);
        List<Comic> comics = result.getRecords();
        if (comics.isEmpty()) {
            IPage<ComicListVO> emptyPage = result.convert(comic ->
                    toListVO(comic, new HashMap<>(), new HashMap<>()));
            return ComicListPage.from(emptyPage);
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
        Map<Long, ReadingHistory> histories = historyMapper.selectList(
                        new LambdaQueryWrapper<ReadingHistory>().in(ReadingHistory::getComicId, comicIds))
                .stream()
                .collect(Collectors.toMap(ReadingHistory::getComicId, history -> history));

        IPage<ComicListVO> voPage = result.convert(
                comic -> toListVO(comic, categoryNames, histories));
        return ComicListPage.from(voPage);
    }

    /**
     * 生成查询缓存键：规范化全部查询条件后取 MD5 摘要，避免超长 key。
     * 同条件同键、不同条件不同键；loadPage 的 @Cacheable 引用此方法。
     */
    public String cacheKey(ComicListQuery query) {
        String raw = "v2|" + String.join("|",
                nz(query.getKeyword()),
                nz(query.getTag()),
                query.getTags() == null ? "" : String.join(",", query.getTags()),
                nz(query.getTagMode()),
                nz(query.getStatus()),
                nz(query.getCategory()),
                nz(query.getSourceType()),
                nz(query.getSort()),
                String.valueOf(query.getPage()),
                String.valueOf(query.getSize()));
        return md5(raw);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
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
        vo.setStatus(toStatus(comic.getStatus() == null ? null : comic.getStatus().name()));
        vo.setCreatedAt(comic.getCreatedAt());

        ReadingHistory history = histories.get(comic.getId());
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / comic.getTotalPages());
        }
        return vo;
    }

    private static ComicStatus toStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ComicStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
