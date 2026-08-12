package com.comicatlas.api.comic.cache;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.comic.dto.CatalogNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 目录缓存失效器测试（管理域）。
 * <p>
 * 管理端写操作（回收/恢复/重排等）后通过 CatalogCacheInvalidator 失效目录缓存，
 * 失效延迟到事务提交后执行；回滚不失效。阅读服务 buildTree 读取同一 Redis 缓存。
 */
@SpringJUnitConfig(CatalogCacheInvalidatorTest.TestConfig.class)
class CatalogCacheInvalidatorTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CatalogCacheInvalidator cacheInvalidator;

    @Test
    void evict_shouldRemoveOnlySpecifiedComic() {
        var cache = cacheManager.getCache(CatalogCacheInvalidator.CACHE_NAME);
        if (cache == null) {
            throw new AssertionError("目录缓存未创建");
        }
        cache.put(1L, List.of(new CatalogNode(1L, "目录")));
        cache.put(2L, List.of(new CatalogNode(2L, "其他目录")));

        cacheInvalidator.evict(1L);

        assertNull(cache.get(1L));
        assertInstanceOf(List.class, cache.get(2L).get());
    }

    @Test
    void evict_shouldClearComicListCache() {
        var catalogCache = cacheManager.getCache(CatalogCacheInvalidator.CACHE_NAME);
        var comicListCache = cacheManager.getCache(ComicReferenceCache.COMIC_LIST);
        if (catalogCache == null || comicListCache == null) {
            throw new AssertionError("缓存未创建");
        }
        catalogCache.put(1L, List.of(new CatalogNode(1L, "目录")));
        comicListCache.put("筛选条件一", "页面一");
        comicListCache.put("筛选条件二", "页面二");

        cacheInvalidator.evict(1L);

        assertNull(catalogCache.get(1L));
        assertNull(comicListCache.get("筛选条件一"));
        assertNull(comicListCache.get("筛选条件二"));
    }

    @Test
    void evict_shouldWaitUntilTransactionCommit() {
        var cache = cacheManager.getCache(CatalogCacheInvalidator.CACHE_NAME);
        if (cache == null) {
            throw new AssertionError("目录缓存未创建");
        }
        cache.put(1L, List.of(new CatalogNode(1L, "目录")));

        TransactionSynchronizationManager.initSynchronization();
        try {
            cacheInvalidator.evict(1L);
            assertNotNull(cache.get(1L));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertNull(cache.get(1L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evict_shouldNotInvalidateOnRollback() {
        var cache = cacheManager.getCache(CatalogCacheInvalidator.CACHE_NAME);
        if (cache == null) {
            throw new AssertionError("目录缓存未创建");
        }
        cache.put(1L, List.of(new CatalogNode(1L, "目录")));

        TransactionSynchronizationManager.initSynchronization();
        try {
            cacheInvalidator.evict(1L);
            assertNotNull(cache.get(1L));

            // 事务回滚：afterCompletion(STATUS_ROLLED_BACK) 不应触发缓存失效，旧缓存仍有效
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertNotNull(cache.get(1L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    CatalogCacheInvalidator.CACHE_NAME, ComicReferenceCache.COMIC_LIST);
        }

        @Bean
        CatalogCacheInvalidator catalogCacheInvalidator(CacheManager cacheManager) {
            return new CatalogCacheInvalidator(cacheManager);
        }
    }
}
