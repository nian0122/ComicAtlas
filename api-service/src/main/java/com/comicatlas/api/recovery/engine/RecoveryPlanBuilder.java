package com.comicatlas.api.recovery.engine;

import com.comicatlas.api.recovery.domain.RestoreContext;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 解析并校验恢复元数据，生成不含数据库写操作的恢复计划。 */
@Component
@RequiredArgsConstructor
public class RecoveryPlanBuilder {
    private final RecoveryMediaResolver recoveryMediaResolver;

    public RecoveryPlan build(Map<String, Object> metadata, RestoreContext context) {
        Map<String, Object> comicData = asMap(metadata.get("comic"), "comic");
        List<Map<String, Object>> catalogs = asMapList(metadata.get("catalogs"), "catalogs");
        List<Map<String, Object>> chapters = asMapList(metadata.get("chapters"), "chapters");
        validateIndexes(catalogs, chapters);
        List<List<ResolvedMediaItem>> media = recoveryMediaResolver.resolveMedia(context.comicId(), chapters);
        return new RecoveryPlan(comicData, catalogs, chapters, media, context);
    }

    private static void validateIndexes(List<Map<String, Object>> catalogs,
                                        List<Map<String, Object>> chapters) {
        int catalogCount = catalogs.size();
        for (Map<String, Object> catalog : catalogs) {
            validateIndex(catalog.get("parentIndex"), catalogCount, "catalog parentIndex");
        }
        for (Map<String, Object> chapter : chapters) {
            validateIndex(chapter.get("catalogIndex"), catalogCount, "chapter catalogIndex");
        }
    }

    private static void validateIndex(Object value, int count, String field) {
        if (value == null) return;
        int index = ((Number) value).intValue();
        if (index < 0 || index >= count) {
            throw new BusinessException(field + " 越界: index=" + index + ", catalogCount=" + count);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new BusinessException("metadata 字段类型非法: " + field);
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value, String field) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new BusinessException("metadata 字段类型非法: " + field);
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new BusinessException("metadata 字段元素类型非法: " + field);
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    public record RecoveryPlan(Map<String, Object> comicData,
                               List<Map<String, Object>> catalogs,
                               List<Map<String, Object>> chapters,
                               List<List<ResolvedMediaItem>> resolvedMedia,
                               RestoreContext context) { }
}
