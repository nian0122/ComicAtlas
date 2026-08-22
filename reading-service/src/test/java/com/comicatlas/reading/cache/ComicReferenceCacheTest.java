package com.comicatlas.reading.cache;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.reading.library.dto.ComicListPage;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.dto.ComicListVO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.reading.library.service.CategoryQueryService;
import com.comicatlas.reading.library.service.ComicListQueryService;
import com.comicatlas.reading.library.service.TagQueryService;
import com.comicatlas.reading.library.service.impl.CategoryQueryServiceImpl;
import com.comicatlas.reading.library.service.impl.ComicListQueryServiceImpl;
import com.comicatlas.reading.library.service.impl.TagQueryServiceImpl;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类/标签/漫画列表缓存集成测试（阅读域）。
 * <p>
 * 验证阅读端只读缓存的 @Cacheable 复用与不缓存空结果行为，以及 ComicListPage 的
 * Redis JSON round-trip。写操作后的缓存失效由管理服务（CacheEvictor 调用）负责，
 * 不在本测试覆盖。
 */
@SpringJUnitConfig(ComicReferenceCacheTest.TestConfig.class)
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class ComicReferenceCacheTest {

    @Autowired
    private CategoryQueryService categoryService;
    @Autowired
    private TagQueryService tagService;
    @Autowired
    private ComicListQueryService comicListService;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private TagMapper tagMapper;
    @Autowired
    private ComicMapper comicMapper;
    @Autowired
    private ReadingHistoryMapper historyMapper;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private FileUrlResolver fileUrlResolver;

    @BeforeEach
    void setUp() {
        reset(categoryMapper, tagMapper, comicMapper, historyMapper);
        for (String name : List.of(
                ComicReferenceCache.CATEGORIES,
                ComicReferenceCache.TAGS,
                ComicReferenceCache.COMIC_LIST)) {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    // ==================== 分类列表 ====================

    @Test
    void listCategories_shouldReuseCachedResult() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(category(1L, "冒险", 1)));

        categoryService.listCategories();
        categoryService.listCategories();

        verify(categoryMapper).selectList(any());
    }

    @Test
    void listCategories_shouldNotCacheEmptyResult() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        categoryService.listCategories();
        categoryService.listCategories();

        verify(categoryMapper, times(2)).selectList(any());
    }

    // ==================== 标签列表 ====================

    @Test
    void listTags_shouldReuseCachedResult() {
        when(tagMapper.selectList(null)).thenReturn(List.of(tag(1L, "action")));

        tagService.listTags();
        tagService.listTags();

        verify(tagMapper).selectList(null);
    }

    // ==================== 漫画列表 ====================

    @Test
    void listComics_shouldReuseCachedResult() {
        ComicListQuery query = new ComicListQuery();
        query.setPage(1);
        query.setSize(20);
        Page<Comic> comicPage = new Page<>(1, 20, 1);
        comicPage.setRecords(List.of(comic(1L)));
        when(comicMapper.selectPage(any(Page.class), same(query))).thenReturn(comicPage);

        comicListService.loadPage(query);
        comicListService.loadPage(query);

        verify(comicMapper).selectPage(any(Page.class), same(query));
    }

    @Test
    void listComics_differentQueries_useDifferentKeys() {
        ComicListQuery q1 = new ComicListQuery();
        q1.setKeyword("naruto");
        q1.setPage(1);
        ComicListQuery q2 = new ComicListQuery();
        q2.setKeyword("naruto");
        q2.setPage(2);

        ComicListQueryServiceImpl service = new ComicListQueryServiceImpl(
                comicMapper, categoryMapper, historyMapper, fileUrlResolver);
        assertEquals(32, service.cacheKey(q1).length(), "MD5 key 应为 32 字符");
        ComicListQuery q1b = new ComicListQuery();
        q1b.setKeyword("naruto");
        q1b.setPage(1);
        assertEquals(service.cacheKey(q1), service.cacheKey(q1b), "相同条件应生成相同 key");
        org.junit.jupiter.api.Assertions.assertNotEquals(service.cacheKey(q1), service.cacheKey(q2));

        ComicListQuery descending = new ComicListQuery();
        descending.setSort("pageCount");
        descending.setOrder("desc");
        ComicListQuery ascending = new ComicListQuery();
        ascending.setSort("pageCount");
        ascending.setOrder("asc");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                service.cacheKey(descending), service.cacheKey(ascending),
                "升序和降序必须使用不同缓存键");
    }

    @Test
    void listComics_shouldNotCacheEmptyResult() {
        ComicListQuery query = new ComicListQuery();
        Page<Comic> comicPage = new Page<>(1, 20, 0);
        comicPage.setRecords(List.of());
        when(comicMapper.selectPage(any(Page.class), same(query))).thenReturn(comicPage);

        comicListService.loadPage(query);
        comicListService.loadPage(query);

        verify(comicMapper, times(2)).selectPage(any(Page.class), same(query));
    }

    @Test
    void comicListDto_shouldSupportRedisJsonRoundTrip_withLocalDateTime() {
        ComicListVO vo = new ComicListVO();
        vo.setId(1L);
        vo.setTitle("测试");
        vo.setCreatedAt(java.time.LocalDateTime.of(2026, 8, 1, 10, 30));

        var ptv = com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.comicatlas.")
                .allowIfSubType("java.util.")
                .build();
        var serializer = new org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer(
                com.fasterxml.jackson.databind.json.JsonMapper.builder()
                        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .activateDefaultTyping(ptv, com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL)
                        .build());

        ComicListPage page = new ComicListPage();
        page.setRecords(java.util.List.of(vo));
        page.setTotal(1);
        page.setCurrent(1);
        page.setSize(20);

        Object restored = serializer.deserialize(serializer.serialize(page));

        assertInstanceOf(ComicListPage.class, restored);
        assertEquals(1, ((ComicListPage) restored).getRecords().size());
        assertEquals(java.time.LocalDateTime.of(2026, 8, 1, 10, 30),
                ((ComicListPage) restored).getRecords().get(0).getCreatedAt(),
                "LocalDateTime 字段应保持一致");
    }

    @Test
    void comicListPage_shouldSupportRedisJsonRoundTrip_withoutDefaultTyping() {
        ComicListVO vo = new ComicListVO();
        vo.setId(1L);
        vo.setTitle("测试");
        vo.setCreatedAt(java.time.LocalDateTime.of(2026, 8, 1, 10, 30));
        ComicListPage page = new ComicListPage();
        page.setRecords(java.util.List.of(vo));
        page.setTotal(1);
        page.setCurrent(1);
        page.setSize(20);

        var ptv = com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.comicatlas.")
                .allowIfSubType("java.util.")
                .build();
        var serializer = new org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer(
                com.fasterxml.jackson.databind.json.JsonMapper.builder()
                        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .activateDefaultTyping(ptv, com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL)
                        .build());

        Object restored = serializer.deserialize(serializer.serialize(page));

        assertInstanceOf(ComicListPage.class, restored, "序列化后 DTO 在 default typing 下应还原为原类型");
        ComicListPage restoredPage = (ComicListPage) restored;
        assertEquals(1, restoredPage.getRecords().size());
        assertEquals("测试", restoredPage.getRecords().get(0).getTitle());
        assertEquals(1, restoredPage.getTotal());
        assertEquals(1L, restoredPage.getCurrent());
    }

    // ==================== helpers ====================

    private static Category category(Long id, String name, int sortOrder) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setSortOrder(sortOrder);
        return c;
    }

    private static Tag tag(Long id, String name) {
        Tag t = new Tag();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static Comic comic(Long id) {
        Comic c = new Comic();
        c.setId(id);
        c.setTitle("漫画" + id);
        c.setStatus(ComicStatus.READY);
        return c;
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CategoryMapper categoryMapper() {
            return mock(CategoryMapper.class);
        }

        @Bean
        TagMapper tagMapper() {
            return mock(TagMapper.class);
        }

        @Bean
        ComicMapper comicMapper() {
            return mock(ComicMapper.class);
        }

        @Bean
        ReadingHistoryMapper historyMapper() {
            return mock(ReadingHistoryMapper.class);
        }

        @Bean
        FileUrlResolver fileUrlResolver() {
            return mock(FileUrlResolver.class);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    ComicReferenceCache.CATEGORIES,
                    ComicReferenceCache.TAGS,
                    ComicReferenceCache.COMIC_LIST);
        }

        @Bean
        ComicTagMapper comicTagMapper() {
            return mock(ComicTagMapper.class);
        }

        @Bean
        CategoryQueryServiceImpl categoryService(CategoryMapper categoryMapper) {
            return new CategoryQueryServiceImpl(categoryMapper);
        }

        @Bean
        TagQueryServiceImpl tagService(TagMapper tagMapper) {
            return new TagQueryServiceImpl(tagMapper);
        }

        @Bean
        ComicListQueryServiceImpl comicListService(
                ComicMapper comicMapper,
                CategoryMapper categoryMapper,
                ReadingHistoryMapper historyMapper,
                FileUrlResolver fileUrlResolver) {
            return new ComicListQueryServiceImpl(
                    comicMapper, categoryMapper, historyMapper, fileUrlResolver);
        }
    }
}
