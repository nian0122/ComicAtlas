package com.comicatlas.api.comic.cache;

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

    public static final String CACHE_NAME = "comicCatalog";

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
