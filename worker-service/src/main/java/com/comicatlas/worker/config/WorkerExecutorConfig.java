package com.comicatlas.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Worker 统一线程池配置 — 外部进程输出读取与转码并行。
 */
@Configuration
public class WorkerExecutorConfig {

    @Bean(name = "videoNormalizeExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor videoNormalizeExecutor(
            @Value("${worker.executor.video-normalize-threads:2}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("video-normalizer-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
