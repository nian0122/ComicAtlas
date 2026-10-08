package com.comicatlas.api.storage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 扫描隔离于 HTTP 与 Outbox 调度线程，单线程和有界队列控制磁盘负载。 */
@Configuration
public class StorageStatisticsConfig {
    @Bean("thumbnailScanExecutor")
    public ThreadPoolTaskExecutor thumbnailScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("thumbnail-capacity-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
