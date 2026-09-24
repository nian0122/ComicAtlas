package com.comicatlas.reading.cache;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.reading.library.service.CategoryQueryService;
import com.comicatlas.reading.library.service.TagQueryService;
import com.comicatlas.reading.library.service.impl.CategoryQueryServiceImpl;
import com.comicatlas.reading.library.service.impl.TagQueryServiceImpl;
import com.comicatlas.reading.testutil.MybatisPlusLambdaCacheExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类/标签缓存集成测试（阅读域）。
 * <p>
 * 验证阅读端分类与标签只读缓存的复用及空结果不缓存行为。
 */
@SpringJUnitConfig(ComicReferenceCacheTest.TestConfig.class)
@ExtendWith(MybatisPlusLambdaCacheExtension.class)
class ComicReferenceCacheTest {

    @Autowired
    private CategoryQueryService categoryService;
    @Autowired
    private TagQueryService tagService;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private TagMapper tagMapper;
    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        reset(categoryMapper, tagMapper);
        for (String name : List.of(
                ComicReferenceCache.CATEGORIES,
                ComicReferenceCache.TAGS)) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    // ==================== 分类列表 ====================

    @Test
    void listCategories_shouldReuseCachedResult() {
        when(categoryMapper.selectAllOrderedBySortOrder()).thenReturn(List.of(category(1L, "冒险", 1)));

        categoryService.listCategories();
        categoryService.listCategories();

        verify(categoryMapper).selectAllOrderedBySortOrder();
    }

    @Test
    void listCategories_shouldNotCacheEmptyResult() {
        when(categoryMapper.selectAllOrderedBySortOrder()).thenReturn(List.of());

        categoryService.listCategories();
        categoryService.listCategories();

        verify(categoryMapper, times(2)).selectAllOrderedBySortOrder();
    }

    // ==================== 标签列表 ====================

    @Test
    void listTags_shouldReuseCachedResult() {
        when(tagMapper.selectList(null)).thenReturn(List.of(tag(1L, "action")));

        tagService.listTags();
        tagService.listTags();

        verify(tagMapper).selectList(null);
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
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(
                    ComicReferenceCache.CATEGORIES,
                    ComicReferenceCache.TAGS);
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

    }
}
