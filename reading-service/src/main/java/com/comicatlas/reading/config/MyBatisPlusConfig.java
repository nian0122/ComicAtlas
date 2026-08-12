package com.comicatlas.reading.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.comicatlas.api.common.handler.EnumTypeHandlers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.comicatlas.api.*.mapper")
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 注册共享枚举 TypeHandler（comic-shared），数据库 VARCHAR 与 Java 枚举双向映射。
     * <p>
     * 与管理服务保持一致的安全解析语义（未知枚举值返回 null 并告警），
     * 保证阅读端读取漫画/章节/媒体等表的历史数据行为一致。
     */
    @Bean
    public ConfigurationCustomizer enumTypeHandlerCustomizer() {
        return configuration -> {
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.SourceTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.ComicStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.HqStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.LqStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.ChapterLifecycleStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.MediaLifecycleStatusHandler.class);
        };
    }
}
