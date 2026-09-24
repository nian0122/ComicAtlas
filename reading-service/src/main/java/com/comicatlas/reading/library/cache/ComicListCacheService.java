package com.comicatlas.reading.library.cache;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.dto.ComicListPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/** 漫画列表基础数据缓存，阅读进度不进入缓存。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComicListCacheService {

    private static final String KEY_VERSION = "v1";

    private final CacheManager cacheManager;

    public ComicListPage get(String cacheKey) {
        Cache cache = cacheManager.getCache(ComicReferenceCache.COMIC_LIST);
        if (cache == null) {
            return null;
        }
        try {
            Cache.ValueWrapper valueWrapper = cache.get(cacheKey);
            return valueWrapper == null ? null : (ComicListPage) valueWrapper.get();
        } catch (RuntimeException exception) {
            log.warn("读取漫画列表缓存失败，继续查询数据库: key={}", cacheKey, exception);
            return null;
        }
    }

    public void put(String cacheKey, ComicListPage page) {
        Cache cache = cacheManager.getCache(ComicReferenceCache.COMIC_LIST);
        if (cache == null) {
            return;
        }
        try {
            cache.put(cacheKey, page);
        } catch (RuntimeException exception) {
            log.warn("写入漫画列表缓存失败，继续使用数据库结果: key={}", cacheKey, exception);
        }
    }

    public String buildKey(ComicListQuery query) {
        StringJoiner keyBuilder = new StringJoiner("&", "comic-list:" + KEY_VERSION + "?", "");
        addKeyPart(keyBuilder, "keyword", query.getKeyword());
        addKeyPart(keyBuilder, "tag", query.getTag());
        List<String> normalizedTags = query.getTags() == null
                ? List.of()
                : query.getTags().stream().sorted(Comparator.naturalOrder()).toList();
        addKeyPart(keyBuilder, "tags", String.join(",", normalizedTags));
        addKeyPart(keyBuilder, "tagMode", query.getTagMode());
        addKeyPart(keyBuilder, "status", query.getStatus());
        addKeyPart(keyBuilder, "category", query.getCategory());
        addKeyPart(keyBuilder, "sourceType", query.getSourceType());
        addKeyPart(keyBuilder, "sort", query.getSort());
        addKeyPart(keyBuilder, "order", query.getOrder());
        addKeyPart(keyBuilder, "page", String.valueOf(query.getPage()));
        addKeyPart(keyBuilder, "size", String.valueOf(query.getSize()));
        return keyBuilder.toString();
    }

    private static void addKeyPart(StringJoiner keyBuilder, String name, String value) {
        String encodedValue = value == null
                ? ""
                : URLEncoder.encode(value, StandardCharsets.UTF_8);
        keyBuilder.add(name + "=" + encodedValue);
    }
}
