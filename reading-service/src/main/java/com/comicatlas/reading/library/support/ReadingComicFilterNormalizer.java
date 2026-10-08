package com.comicatlas.reading.library.support;

import com.comicatlas.contract.comic.dto.ComicListQuery;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 阅读筛选仅返回可阅读漫画，独立于管理端的生命周期及文件完整性查询。 */
public final class ReadingComicFilterNormalizer {

    public static final int MIN_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 24;
    public static final int MAX_PAGE_SIZE = 60;

    private static final Set<String> SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "title", "pageCount", "lastReadTime", "fileSize");

    private ReadingComicFilterNormalizer() {
    }

    public static void normalize(ComicListQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("漫画列表查询参数不能为空");
        }
        query.setKeyword(trimToNull(query.getKeyword()));
        query.setTag(trimToNull(query.getTag()));
        query.setCategory(trimToNull(query.getCategory()));
        query.setSourceType(trimToNull(query.getSourceType()));
        query.setStatus("READY");
        String tagMode = query.getTagMode();
        if ("AND".equalsIgnoreCase(tagMode)) {
            query.setTagMode("AND");
        } else if ("NOT".equalsIgnoreCase(tagMode)) {
            query.setTagMode("NOT");
        } else {
            query.setTagMode("OR");
        }
        query.setSort(SORT_FIELDS.contains(query.getSort()) ? query.getSort() : "createdAt");
        query.setOrder("asc".equalsIgnoreCase(query.getOrder()) ? "asc" : "desc");
        query.setPage(query.getPage() == null ? MIN_PAGE : Math.max(MIN_PAGE, query.getPage()));
        query.setSize(query.getSize() == null
                ? DEFAULT_PAGE_SIZE
                : Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE, query.getSize())));

        if (query.getTags() == null || query.getTags().isEmpty()) {
            query.setTags(null);
            return;
        }
        LinkedHashSet<String> normalizedTags = new LinkedHashSet<>();
        for (String tag : query.getTags()) {
            String normalizedTag = trimToNull(tag);
            if (normalizedTag != null) {
                normalizedTags.add(normalizedTag);
            }
        }
        if (normalizedTags.contains("_NONE")) {
            query.setTags(List.of("_NONE"));
            query.setTag(null);
            query.setTagMode("OR");
        } else {
            query.setTags(normalizedTags.isEmpty() ? null : List.copyOf(normalizedTags));
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
