package com.comicatlas.api.comic.cache;

import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.controller.CatalogController;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.service.CatalogService;
import com.comicatlas.api.comic.service.impl.CatalogServiceImpl;
import com.comicatlas.api.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(CatalogCacheTest.TestConfig.class)
class CatalogCacheTest {

    @Autowired
    private CatalogService catalogService;
    @Autowired
    private CatalogCacheInvalidator cacheInvalidator;
    @Autowired
    private CatalogMapper catalogMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        reset(catalogMapper, chapterMapper);
        var cache = cacheManager.getCache(CatalogCacheInvalidator.CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void buildTree_shouldReuseCachedResult() {
        Chapter chapter = new Chapter();
        chapter.setId(11L);
        chapter.setComicId(1L);
        chapter.setChapterNo("1");
        chapter.setTitle("第一章");
        chapter.setGlobalOrder(0);
        chapter.setPageCount(20);
        when(catalogMapper.selectList(any())).thenReturn(List.of());
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));

        catalogService.buildTree(1L);
        catalogService.buildTree(1L);

        verify(catalogMapper).selectList(any());
        verify(chapterMapper).selectList(any());
    }

    @Test
    void catalogEndpoint_shouldReturnCachedTree() throws Exception {
        Chapter chapter = new Chapter();
        chapter.setId(11L);
        chapter.setComicId(1L);
        chapter.setChapterNo("1");
        chapter.setTitle("第一章");
        chapter.setGlobalOrder(0);
        chapter.setPageCount(20);
        when(catalogMapper.selectList(any())).thenReturn(List.of());
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogController(catalogService))
                .build();

        mockMvc.perform(get("/api/comics/1/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].chapters[0].title").value("第一章"));
        mockMvc.perform(get("/api/comics/1/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].chapters[0].pageCount").value(20));

        verify(catalogMapper).selectList(any());
        verify(chapterMapper).selectList(any());
    }

    @Test
    void buildTree_shouldNotCacheEmptyResult() {
        when(catalogMapper.selectList(any())).thenReturn(List.of());
        when(chapterMapper.selectList(any())).thenReturn(List.of());

        catalogService.buildTree(2L);
        catalogService.buildTree(2L);

        verify(catalogMapper, times(2)).selectList(any());
        verify(chapterMapper, times(2)).selectList(any());
    }

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
    void catalogDto_shouldSupportRedisJsonRoundTrip() {
        var serializer = new GenericJackson2JsonRedisSerializer();
        List<CatalogNode> original = new ArrayList<>();
        original.add(new CatalogNode(1L, "目录"));

        Object restored = serializer.deserialize(serializer.serialize(original));

        List<?> restoredList = assertInstanceOf(List.class, restored);
        CatalogNode restoredNode = assertInstanceOf(CatalogNode.class, restoredList.get(0));
        assertEquals("目录", restoredNode.getTitle());
    }

    @Test
    void redisConfig_shouldApplyCatalogTtl() {
        RedisConfig redisConfig = new RedisConfig();
        RedisCacheManager redisCacheManager = assertInstanceOf(
                RedisCacheManager.class,
                redisConfig.cacheManager(
                        mock(RedisConnectionFactory.class), Duration.ofMinutes(12),
                        Duration.ofMinutes(30), Duration.ofSeconds(60)));
        redisCacheManager.afterPropertiesSet();

        assertEquals(
                Duration.ofMinutes(12),
                redisCacheManager.getCacheConfigurations()
                        .get(CatalogCacheInvalidator.CACHE_NAME)
                        .getTtlFunction()
                        .getTimeToLive(1L, null));
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CatalogMapper catalogMapper() {
            return mock(CatalogMapper.class);
        }

        @Bean
        ChapterMapper chapterMapper() {
            return mock(ChapterMapper.class);
        }

        @Bean
        CatalogService catalogService(CatalogMapper catalogMapper, ChapterMapper chapterMapper) {
            return new CatalogServiceImpl(catalogMapper, chapterMapper);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CatalogCacheInvalidator.CACHE_NAME);
        }

        @Bean
        CatalogCacheInvalidator catalogCacheInvalidator(CacheManager cacheManager) {
            return new CatalogCacheInvalidator(cacheManager);
        }
    }
}
