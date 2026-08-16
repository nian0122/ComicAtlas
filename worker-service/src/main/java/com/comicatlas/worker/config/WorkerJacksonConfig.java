package com.comicatlas.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 的 JSON 序列化配置。
 *
 * <p>单独拆分该配置，避免 {@link WorkerConfig} 同时承担配置绑定和基础设施 Bean 注册职责。</p>
 */
@Configuration(proxyBeanMethods = false)
public class WorkerJacksonConfig {

    /**
     * 创建支持 Java 时间类型的 JSON 映射器。
     *
     * @return Worker 使用的 JSON 映射器
     */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }
}
