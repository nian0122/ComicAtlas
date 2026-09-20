package com.comicatlas.ai.config;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 异步任务执行器配置。 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
    @Bean(name = "analysisExecutor")
    public Executor analysisExecutor(AiProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int concurrency = Math.max(1, properties.maxConcurrentTasks());
        executor.setCorePoolSize(concurrency); executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(100); executor.setThreadNamePrefix("comic-ai-");
        executor.initialize(); return executor;
    }
}
