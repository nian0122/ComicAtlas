package com.comicatlas.ai.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 允许管理端跨端口访问 AI 任务 API，来源通过环境变量限制。 */
@Configuration
public class AiWebConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    public AiWebConfig(@Value("${comic-ai.cors-origins:http://localhost,http://localhost:80,http://127.0.0.1}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new);
    }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins).allowedMethods("GET", "POST", "OPTIONS").allowedHeaders("Content-Type").maxAge(3600);
    }
}
