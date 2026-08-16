package com.comicatlas.reading.cache;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.reading.dto.CatalogNode;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.reading.config.RedisConfig;
import com.comicatlas.reading.controller.CatalogController;
import com.comicatlas.reading.service.CatalogService;
import com.comicatlas.reading.service.impl.CatalogServiceImpl;
import com.comicatlas.reading.testutil.MybatisPlusLambdaCacheExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阅读端目录树缓存测试。
 * <p>
 * 验证 CatalogServiceImpl.buildTree 的 @Cacheable 复用与不缓存空结果，以及目录 DTO 的
 * Redis JSON round-trip。缓存失效（管理端写操作后 evict）由管理服务 CatalogCacheInvalidator
 * 负责，其测试位于 api-service。
 */
@SpringJUnitConfig(CatalogCacheTest.TestConfig.class)
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class CatalogCacheTest {

    @Autowired
    private CatalogService catalogService;
    @Autowired
    private CatalogMapper catalogMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private ComicMapper comicMapper;
    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        reset(catalogMapper, chapterMapper, comicMapper);
        Comic comic = new Comic();
        comic.setId(1L);
        comic.setStatus(ComicStatus.READY);
        when(comicMapper.selectOne(any())).thenReturn(comic);
        var cache = cacheManager.getCache(ComicReferenceCache.CATALOG);
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
                        .get(ComicReferenceCache.CATALOG)
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
        ComicMapper comicMapper() {
            return mock(ComicMapper.class);
        }

        @Bean
        CatalogService catalogService(CatalogMapper catalogMapper, ChapterMapper chapterMapper,
                                      ComicMapper comicMapper) {
            return new CatalogServiceImpl(catalogMapper, chapterMapper, comicMapper);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(ComicReferenceCache.CATALOG);
        }
    }
}
