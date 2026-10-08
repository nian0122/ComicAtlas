package com.comicatlas.api.library.support;

import com.comicatlas.api.library.dto.ManagementComicListQuery;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.contract.common.exception.BusinessException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 管理端筛选唯一归一化入口；未知状态直接拒绝，避免静默返回误导结果。 */
public final class ManagementComicFilterNormalizer {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String NONE_FILTER = "_NONE";
    private static final Set<String> TAG_MODES = Set.of("OR", "AND", "NOT");
    private static final Set<String> SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "title", "pageCount", "lastReadTime", "fileSize");
    private static final Set<String> HQ_FILTERS = Set.of("HAS_HQ", "ALL_HQ", "PARTIAL_HQ", "NO_HQ",
            "READY", "PENDING", "MISSING", "DELETE_QUEUED", "DELETING", "DELETED", "FAILED");
    private static final Set<String> LQ_FILTERS = Set.of("HAS_LQ", "ALL_LQ", "PARTIAL_LQ", "NO_LQ",
            "READY", "NOT_GENERATED", "QUEUED", "GENERATING", "MISSING", "FAILED");
    private static final Set<String> COMIC_STATUSES = Arrays.stream(ComicStatus.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> SOURCE_TYPES = Arrays.stream(SourceType.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private ManagementComicFilterNormalizer() {
    }

    /** 处理兼容参数并保留管理端全部生命周期的查询能力。 */
    public static void normalize(ManagementComicListQuery query) {
        query.setKeyword(trimToNull(query.getKeyword()));
        query.setTag(trimToNull(query.getTag()));
        query.setCategory(trimToNull(query.getCategory()));
        query.setStatus(normalizeChoice(query.getStatus(), COMIC_STATUSES, "漫画状态"));
        query.setSourceType(normalizeChoice(query.getSourceType(), SOURCE_TYPES, "来源类型"));
        query.setHqStatus(normalizeChoice(query.getHqStatus(), HQ_FILTERS, "HQ 状态"));
        query.setLqStatus(normalizeChoice(query.getLqStatus(), LQ_FILTERS, "LQ 状态"));
        String tagMode = normalizeChoice(query.getTagMode(), TAG_MODES, "标签匹配模式");
        query.setTagMode(tagMode == null ? "OR" : tagMode);
        query.setSort(SORT_FIELDS.contains(Objects.toString(query.getSort(), "")) ? query.getSort() : "createdAt");
        query.setOrder("asc".equalsIgnoreCase(query.getOrder()) ? "asc" : "desc");
        query.setPage(query.getPage() == null ? 1 : Math.max(1, query.getPage()));
        query.setSize(query.getSize() == null ? DEFAULT_PAGE_SIZE
                : Math.min(MAX_PAGE_SIZE, Math.max(1, query.getSize())));
        List<String> tags = query.getTags() == null ? List.of() : query.getTags().stream()
                .map(ManagementComicFilterNormalizer::trimToNull).filter(Objects::nonNull).distinct().toList();
        if (tags.contains(NONE_FILTER)) {
            query.setTags(List.of(NONE_FILTER));
            query.setTag(null);
            query.setTagMode("OR");
        } else {
            query.setTags(tags.isEmpty() ? null : tags);
            if (tags.isEmpty()) {
                query.setTagMode("OR");
            }
        }
    }

    private static String normalizeChoice(String value, Set<String> choices, String fieldLabel) {
        String normalizedValue = trimToNull(value);
        if (normalizedValue == null) {
            return null;
        }
        normalizedValue = normalizedValue.toUpperCase(Locale.ROOT);
        if (!choices.contains(normalizedValue)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, fieldLabel + "筛选值无效");
        }
        return normalizedValue;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmedValue = value.trim();
        return trimmedValue.isEmpty() ? null : trimmedValue;
    }
}
