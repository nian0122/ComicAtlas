package com.comicatlas.api.management.batch.service;

import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.management.batch.dto.BatchSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量选择解析器 — 将 IDS / FILTER 判别联合解析为稳定排序的漫画 id 列表。
 * <p>
 * IDS：去重后按 id 升序；FILTER：按筛选条件查询后去除 excludedIds，按 id 升序。
 * limit 用于探测超限（调用方传 maxItems + 1）。
 */
@Component
@RequiredArgsConstructor
public class BatchSelectionResolver {

    private final ComicMapper comicMapper;

    /**
     * @param selection 判别联合
     * @param limit     最多解析数量（超限探测：maxItems + 1）
     * @return 去重、稳定排序（id ASC）的漫画 id 列表
     */
    public List<Long> resolve(BatchSelection selection, int limit) {
        if (selection instanceof BatchSelection.Ids ids) {
            return dedupSorted(ids.getIds());
        }
        if (selection instanceof BatchSelection.Filter filter) {
            List<Long> matched = comicMapper.selectIdsByFilter(filter.getQuery(), limit);
            Set<Long> excluded = new LinkedHashSet<>(
                    filter.getExcludedIds() == null ? List.of() : filter.getExcludedIds());
            return matched.stream()
                    .filter(id -> !excluded.contains(id))
                    .sorted()
                    .toList();
        }
        throw new IllegalArgumentException("未知选择类型: " + selection.getClass().getSimpleName());
    }

    private static List<Long> dedupSorted(List<Long> ids) {
        return ids.stream().distinct().sorted().toList();
    }
}
