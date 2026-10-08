package com.comicatlas.api.task.batch.service;

import com.comicatlas.api.library.dto.ManagementComicListQuery;
import com.comicatlas.api.library.persistence.mapper.ManagementComicListMapper;
import com.comicatlas.api.library.support.ManagementComicFilterNormalizer;
import com.comicatlas.api.task.batch.dto.BatchSelectionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批量选择解析器 — 将 IDS / FILTER 判别联合解析为稳定排序的漫画 id 列表。
 * <p>
 * IDS：去重后按 id 升序；FILTER：按筛选条件查询后去除 excludedIds，按 id 升序。
 * limit 用于探测超限（调用方传 maxItems + 1）。
 */
@Component
@RequiredArgsConstructor
public class BatchSelectionResolver {

    private final ManagementComicListMapper listMapper;

    /**
     * @param selection 判别联合
     * @param limit     最多解析数量（超限探测：maxItems + 1）
     * @return 去重、稳定排序（id ASC）的漫画 id 列表
     */
    public List<Long> resolve(BatchSelectionVO selection, int limit) {
        if (selection instanceof BatchSelectionVO.Ids ids) {
            return dedupSorted(ids.getIds());
        }
        if (selection instanceof BatchSelectionVO.Filter filter) {
            ManagementComicListQuery query = filter.getQuery() == null ? new ManagementComicListQuery() : filter.getQuery();
            ManagementComicFilterNormalizer.normalize(query);
            List<Long> excludedIds = filter.getExcludedIds() == null ? List.of() : dedupSorted(filter.getExcludedIds());
            return listMapper.selectIdsByQuery(query, excludedIds, limit);
        }
        throw new IllegalArgumentException("未知选择类型: " + selection.getClass().getSimpleName());
    }

    private static List<Long> dedupSorted(List<Long> ids) {
        return ids.stream().distinct().sorted().toList();
    }
}
