package com.comicatlas.worker.config;

import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MetadataJsonBuilder 注册。
 * MetadataJsonBuilder 位于 comic-common 的 com.comicatlas.common.metadata，不在 Worker 默认组件扫描范围内，
 * 故在此以 @Bean 方式注册（而非依赖 @Component 扫描）。
 */
@Configuration
public class MetadataJsonBuilderConfig {

    @Bean
    public MetadataJsonBuilder metadataJsonBuilder(ObjectMapper objectMapper) {
        return new MetadataJsonBuilder(objectMapper);
    }
}
