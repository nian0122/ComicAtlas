package com.comicatlas.api.config;

import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MetadataJsonBuilder 注册（位于 comic-common，不在 API 默认扫描范围）。
 */
@Configuration
public class MetadataJsonBuilderConfig {

    @Bean
    public MetadataJsonBuilder metadataJsonBuilder(ObjectMapper objectMapper) {
        return new MetadataJsonBuilder(objectMapper);
    }
}
