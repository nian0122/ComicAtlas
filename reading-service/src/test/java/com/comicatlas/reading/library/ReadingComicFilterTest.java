package com.comicatlas.reading.library;

import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.cache.ComicListCacheService;
import com.comicatlas.reading.library.support.ReadingComicFilterNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 阅读过滤独立于管理生命周期，且不同标签组合不能共享缓存。 */
class ReadingComicFilterTest {
    @Test
    void readingAlwaysUsesReadyScopeAndNormalizesSearchAndTags() {
        ComicListQuery query = new ComicListQuery();
        query.setStatus("TRASHED");
        query.setKeyword(" 漫画 ");
        query.setTags(List.of(" 热血 ", "热血", "冒险", " "));
        query.setTagMode("AND");
        ReadingComicFilterNormalizer.normalize(query);
        assertEquals("READY", query.getStatus());
        assertEquals("漫画", query.getKeyword());
        assertEquals(List.of("热血", "冒险"), query.getTags());
        assertEquals(2, query.getTagCount());
    }

    @Test
    void noTagSelectionCannotRetainExclusionOrLegacySingleTag() {
        ComicListQuery query = new ComicListQuery();
        query.setTag("热血");
        query.setTags(List.of("_NONE", "冒险"));
        query.setTagMode("NOT");
        ReadingComicFilterNormalizer.normalize(query);
        assertEquals(List.of("_NONE"), query.getTags());
        assertEquals("OR", query.getTagMode());
        assertNull(query.getTag());
    }

    @Test
    void cacheSeparatesTagNamesContainingCommasAndIgnoresSelectionOrder() {
        ComicListCacheService cache = new ComicListCacheService(new ConcurrentMapCacheManager());
        ComicListQuery firstQuery = new ComicListQuery();
        firstQuery.setTags(List.of("a,b", "c"));
        ComicListQuery secondQuery = new ComicListQuery();
        secondQuery.setTags(List.of("a", "b,c"));
        assertNotEquals(cache.buildKey(firstQuery), cache.buildKey(secondQuery));
        String firstKey = cache.buildKey(firstQuery);
        firstQuery.setTags(List.of("c", "a,b"));
        assertEquals(firstKey, cache.buildKey(firstQuery));
    }
}
