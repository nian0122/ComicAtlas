package com.comicatlas.reading.testutil;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

/**
 * 为无 Spring 上下文的单元测试注册 MyBatis-Plus 实体元数据（TableInfo）。
 * <p>
 * {@link LambdaQueryWrapper} 的列解析（select/eq 等方法引用 → 列名）依赖
 * {@link TableInfoHelper} 注册的实体元数据；纯 Mockito 单测或 mock-bean 装配的
 * {@code @SpringJUnitConfig} 上下文不会触发 MyBatis-Plus 自动注册。本扩展在
 * 首个使用它的测试类前完成注册，同一 JVM 内静态缓存后续测试共享。
 */
public final class MybatisPlusLambdaCacheExtension implements BeforeAllCallback {

    /** 阅读域查询涉及的全部实体，一次注册避免遗漏 */
    private static final List<Class<?>> ENTITIES = List.of(
            Comic.class, Chapter.class, Catalog.class, Media.class,
            Tag.class, Category.class, ComicTag.class, ReadingHistory.class);

    private static volatile boolean initialized = false;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!initialized) {
            synchronized (MybatisPlusLambdaCacheExtension.class) {
                if (!initialized) {
                    MapperBuilderAssistant assistant =
                            new MapperBuilderAssistant(new MybatisConfiguration(), "");
                    for (Class<?> entity : ENTITIES) {
                        TableInfoHelper.initTableInfo(assistant, entity);
                    }
                    initialized = true;
                }
            }
        }
    }
}
