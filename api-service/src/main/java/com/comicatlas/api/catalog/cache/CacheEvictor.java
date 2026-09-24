package com.comicatlas.api.catalog.cache;

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
     * @param cacheKey  缓存键（如 "all"）
     */
    public void evict(String cacheName, Object cacheKey) {
        Runnable eviction = () -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                try {
                    cache.evict(cacheKey);
                    log.debug("缓存失效: cache={}, key={}", cacheName, cacheKey);
                } catch (IllegalStateException e) {
                    log.warn("缓存失效失败，继续使用数据库结果: cache={}, key={}", cacheName, cacheKey, e);
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

}
