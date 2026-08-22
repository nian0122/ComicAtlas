package com.comicatlas.api.config;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@EnableCaching
@Configuration
@Slf4j
public class RedisConfig implements CachingConfigurer {

    /** Redis 值序列化器：启用默认类型信息（@class）+ JSR-310 模块。缓存值统一为纯数据 DTO，类型信息仅用于反序列化为具体类。 */
    private static final GenericJackson2JsonRedisSerializer VALUE_SERIALIZER =
            new GenericJackson2JsonRedisSerializer(cacheObjectMapper());

    /**
     * 目录缓存专用序列化器：NON_FINAL 类型信息无法 round-trip {@code List.of(...)} 返回的
     * 不可变最终集合（如 ImmutableCollections$List12）——写入时最终类型不带类型包装，读取时
     * 以 Object 期望数组类型包装而失败。改用 EVERYTHING（全部类型携带类型信息）+ PTV（放行
     * java.lang/java.util/com.comicatlas），保证目录树缓存可读写。
     */
    private static final GenericJackson2JsonRedisSerializer CATALOG_VALUE_SERIALIZER =
            new GenericJackson2JsonRedisSerializer(catalogObjectMapper());

    private static ObjectMapper cacheObjectMapper() {
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.comicatlas.")
                .allowIfSubType("java.util.")
                .build();
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL)
                .build();
    }

    private static ObjectMapper catalogObjectMapper() {
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.comicatlas.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .build();
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.EVERYTHING)
                .build();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(VALUE_SERIALIZER);
        return template;
    }

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory factory,
            @Value("${comic.cache.catalog-ttl:30m}") Duration catalogTtl,
            @Value("${comic.cache.reference-ttl:30m}") Duration referenceTtl,
            @Value("${comic.cache.list-ttl:60s}") Duration listTtl,
            @Value("${comic.cache.storage-stats-ttl:5m}") Duration storageStatsTtl) {
        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(VALUE_SERIALIZER));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        // 目录缓存使用 EVERYTHING 专用序列化器，避免最终集合（List.of）无法 round-trip
        cacheConfigs.put(CatalogCacheInvalidator.CACHE_NAME,
                RedisCacheConfiguration.defaultCacheConfig()
                        .disableCachingNullValues()
                        .entryTtl(catalogTtl)
                        .serializeKeysWith(RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(RedisSerializationContext.SerializationPair
                                .fromSerializer(CATALOG_VALUE_SERIALIZER)));
        cacheConfigs.put(ComicReferenceCache.CATEGORIES, baseConfig.entryTtl(referenceTtl));
        cacheConfigs.put(ComicReferenceCache.TAGS, baseConfig.entryTtl(referenceTtl));
        cacheConfigs.put(ComicReferenceCache.COMIC_LIST, baseConfig.entryTtl(listTtl));
        cacheConfigs.put(ComicReferenceCache.STORAGE_STATS, baseConfig.entryTtl(storageStatsTtl));

        return RedisCacheManager.builder(factory)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                logCacheError("读取", exception, cache, key);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                logCacheError("写入", exception, cache, key);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                logCacheError("失效", exception, cache, key);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                logCacheError("清空", exception, cache, null);
            }
        };
    }

    private static void logCacheError(
            String operation, RuntimeException exception, Cache cache, Object key) {
        log.warn("Redis 缓存{}失败，继续使用数据库结果: cache={}, key={}",
                operation, cache.getName(), key, exception);
    }
}
