package com.comicatlas.api.comic.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.ComicListPage;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Tag;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.comic.service.CategoryService;
import com.comicatlas.api.comic.service.ComicListQueryService;
import com.comicatlas.api.comic.service.TagService;
import com.comicatlas.api.comic.service.impl.CategoryServiceImpl;
import com.comicatlas.api.comic.service.impl.ComicListQueryServiceImpl;
import com.comicatlas.api.comic.service.impl.TagServiceImpl;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类/标签/漫画列表缓存集成测试。
 * 复用 CatalogCacheTest 的 SpringJUnitConfig + ConcurrentMapCacheManager 模式，
 * 验证 @Cacheable 命中与失效行为。
 */
@SpringJUnitConfig(ComicReferenceCacheTest.TestConfig.class)
class ComicReferenceCacheTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private TagService tagService;
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
    private CacheEvictor cacheEvictor;
    @Autowired
    private FileUrlResolver fileUrlResolver;
    @Autowired
    private OperationPolicyService operationPolicyService;
    @Autowired
    private ManagementTaskService managementTaskService;

    @BeforeEach
    void setUp() {
        reset(categoryMapper, tagMapper, comicMapper, historyMapper);
        when(managementTaskService.findActiveTasksForComics(any())).thenReturn(java.util.Map.of());
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

    @Test
    void createCategory_shouldEvictListCache() {
        when(categoryMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(categoryMapper.selectList(any())).thenReturn(List.of(category(1L, "冒险", 1)));
        // 预热缓存
        categoryService.listCategories();
        var cache = cacheManager.getCache(ComicReferenceCache.CATEGORIES);
        assertNotNull(cache.get(ComicReferenceCache.ALL_KEY), "预热后缓存应有值");

        // 创建后失效
        categoryService.createCategory("新分类");

        assertNull(cache.get(ComicReferenceCache.ALL_KEY), "创建分类后缓存应失效");
    }

    // ==================== 标签列表 ====================

    @Test
    void listTags_shouldReuseCachedResult() {
        when(tagMapper.selectList(null)).thenReturn(List.of(tag(1L, "action")));

        tagService.listTags();
        tagService.listTags();

        verify(tagMapper).selectList(null);
    }

    @Test
    void createTag_shouldEvictListCache() {
        when(tagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(tagMapper.selectList(null)).thenReturn(List.of(tag(1L, "action")));
        tagService.listTags();
        var cache = cacheManager.getCache(ComicReferenceCache.TAGS);
        assertNotNull(cache.get(ComicReferenceCache.ALL_KEY));

        tagService.createTag("new");

        assertNull(cache.get(ComicReferenceCache.ALL_KEY), "创建标签后缓存应失效");
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
                comicMapper, categoryMapper, historyMapper, fileUrlResolver,
                operationPolicyService, managementTaskService);
        assertEquals(32, service.cacheKey(q1).length(), "MD5 key 应为 32 字符");
        // 同条件同 key
        ComicListQuery q1b = new ComicListQuery();
        q1b.setKeyword("naruto");
        q1b.setPage(1);
        assertEquals(service.cacheKey(q1), service.cacheKey(q1b), "相同条件应生成相同 key");
        // 不同分页不同 key
        org.junit.jupiter.api.Assertions.assertNotEquals(service.cacheKey(q1), service.cacheKey(q2));
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

        // 经 DTO 包装往返（缓存真实形态），验证 LocalDateTime 可序列化
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
                "LocalDateTime 字段应往返一致");
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

        // 与 RedisConfig 相同的序列化器（default typing + JSR-310）
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

        assertInstanceOf(ComicListPage.class, restored, "纯数据 DTO 经 default typing 应还原为具体类");
        ComicListPage restoredPage = (ComicListPage) restored;
        assertEquals(1, restoredPage.getRecords().size());
        assertEquals("测试", restoredPage.getRecords().get(0).getTitle());
        assertEquals(1, restoredPage.getTotal());
        // toPage 组装后应可被调用方用作 IPage
        assertEquals(1L, restoredPage.toPage().getCurrent());
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
        OperationPolicyService operationPolicyService() {
            return mock(OperationPolicyService.class);
        }

        @Bean
        ManagementTaskService managementTaskService() {
            return mock(ManagementTaskService.class);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    ComicReferenceCache.CATEGORIES,
                    ComicReferenceCache.TAGS,
                    ComicReferenceCache.COMIC_LIST);
        }

        @Bean
        CacheEvictor cacheEvictor(CacheManager cacheManager) {
            return new CacheEvictor(cacheManager);
        }

        @Bean
        CategoryServiceImpl categoryService(CategoryMapper categoryMapper, CacheEvictor cacheEvictor) {
            return new CategoryServiceImpl(categoryMapper, cacheEvictor);
        }

        @Bean
        ComicTagMapper comicTagMapper() {
            return mock(ComicTagMapper.class);
        }

        @Bean
        TagServiceImpl tagService(TagMapper tagMapper, ComicTagMapper comicTagMapper, CacheEvictor cacheEvictor) {
            return new TagServiceImpl(tagMapper, comicTagMapper, cacheEvictor);
        }

        @Bean
        ComicListQueryServiceImpl comicListService(
                ComicMapper comicMapper,
                CategoryMapper categoryMapper,
                ReadingHistoryMapper historyMapper,
                FileUrlResolver fileUrlResolver,
                OperationPolicyService operationPolicyService,
                ManagementTaskService managementTaskService) {
            return new ComicListQueryServiceImpl(
                    comicMapper, categoryMapper, historyMapper, fileUrlResolver,
                    operationPolicyService, managementTaskService);
        }
    }
}

