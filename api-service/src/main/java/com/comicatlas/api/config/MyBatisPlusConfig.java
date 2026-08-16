package com.comicatlas.api.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.comicatlas.persistence.handler.EnumTypeHandlers;
import com.comicatlas.api.config.ManagementEnumTypeHandlers;
import com.comicatlas.api.upload.UploadSessionStatusTypeHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
        "com.comicatlas.api.*.mapper",
        "com.comicatlas.persistence.comic.mapper",
        "com.comicatlas.persistence.reader.mapper"
})
public class MyBatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 乐观锁插件（支持 @Version 注解）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 注册自定义枚举 TypeHandler：数据库 VARCHAR 与 Java 枚举双向映射。
     * <p>
     * 默认 {@code EnumTypeHandler} 按 name() 严格匹配，历史脏数据会导致读取抛异常；
     * 自定义 handler 经 {@code safeValueOf} 兜底为 null 并告警，避免运行时崩溃。
     * 共享枚举来自 comic-shared 的 {@code EnumTypeHandlers}，上传会话状态为管理端专属。
     */
    @Bean
    public ConfigurationCustomizer enumTypeHandlerCustomizer() {
        return configuration -> {
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.SourceTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.ComicStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(ManagementEnumTypeHandlers.ImportTaskStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.HqStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.LqStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(ManagementEnumTypeHandlers.ExportTaskStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(ManagementEnumTypeHandlers.RecoveryTaskStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(ManagementEnumTypeHandlers.DirectoryScanTaskStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.ChapterLifecycleStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.MediaLifecycleStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(EnumTypeHandlers.TranscodeStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(ManagementEnumTypeHandlers.ManagementTaskStatusHandler.class);
            configuration.getTypeHandlerRegistry().register(ManagementEnumTypeHandlers.TaskTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(UploadSessionStatusTypeHandler.class);
        };
    }
}
