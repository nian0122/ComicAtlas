package com.comicatlas.api.comic.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 通用缓存失效器：事务提交后失效指定 cache 的 key。
 * 复用 CatalogCacheInvalidator 的 afterCommit 模式，供分类/标签等低频变更缓存使用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictor {

    private final CacheManager cacheManager;

    /**
     * 事务提交后失效指定缓存中的单个 key。
     *
     * @param cacheName 缓存名（如 ComicReferenceCache.CATEGORIES）
     * @param key       缓存的 key（如 "all"）
     */
    public void evict(String cacheName, Object key) {
        Runnable eviction = () -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                try {
                    cache.evict(key);
                    log.debug("缓存失效: cache={}, key={}", cacheName, key);
                } catch (RuntimeException e) {
                    log.warn("缓存失效失败，继续使用数据库结果: cache={}, key={}", cacheName, key, e);
                }
            }
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
     * 清空阅读服务的漫画列表缓存。
     * <p>
     * 列表缓存的 key 包含全部筛选条件，分类或标签变更可能影响多个 key，
     * 因而使用整缓存失效保证跨服务可见的一致性。
     */
    public void evictComicList() {
        evictAll(ComicReferenceCache.COMIC_LIST);
    }

    private void evictAll(String cacheName) {
        Runnable eviction = () -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return;
            }
            try {
                cache.clear();
            } catch (RuntimeException e) {
                log.warn("缓存清空失败，继续使用数据库结果: cache={}", cacheName, e);
            }
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
}
