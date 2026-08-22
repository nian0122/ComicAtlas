package com.comicatlas.api.catalog.cache;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogCacheInvalidator {

    public static final String CACHE_NAME = ComicReferenceCache.CATALOG;

    private final CacheManager cacheManager;

    public void evict(Long comicId) {
        Runnable eviction = () -> {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                try {
                    cache.evict(comicId);
                } catch (RuntimeException e) {
                    log.warn("目录缓存失效失败，继续使用数据库结果: comicId={}", comicId, e);
                }
            }
            evictComicList();
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eviction.run();
                        }
                    });
            return;
        }

        eviction.run();
    }

    /**
     * 漫画列表按筛选条件生成动态缓存键，无法仅按 comicId 精确删除。
     * 管理端任意影响漫画展示或可读状态的操作完成后清空该缓存，
     * 避免阅读服务继续返回旧标题、分类、状态或进度。
     */
    public void evictComicList() {
        Cache cache = cacheManager.getCache(ComicReferenceCache.COMIC_LIST);
        if (cache == null) {
            return;
        }
        try {
            cache.clear();
        } catch (RuntimeException e) {
            log.warn("漫画列表缓存失效失败，继续使用数据库结果", e);
        }
    }
}
