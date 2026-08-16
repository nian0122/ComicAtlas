package com.comicatlas.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Worker 统一线程池配置 — 外部进程输出读取。
 * <p>
 * videoNormalizeExecutor 已随 VideoNormalizer 移除（导入不再做视频标准化转码，
 * 转码统一由 FfmpegTranscoder 单文件执行，无需并行池）。
 */
@Configuration
public class WorkerExecutorConfig {

    @Bean(name = "processIoExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor processIoExecutor(WorkerConfig config) {
        WorkerConfig.Executor executorConfig = config.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(executorConfig.getProcessIoThreads());
        executor.setMaxPoolSize(executorConfig.getProcessIoThreads());
        executor.setQueueCapacity(executorConfig.getProcessIoQueueCapacity());
        executor.setThreadNamePrefix("process-io-");
        // 进程输出读取任务必须异步执行：池饱和时拒绝即抛（AbortPolicy），
        // 避免 CallerRunsPolicy 让读取循环在业务线程同步阻塞，导致 waitFor 超时失效。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(executorConfig.getShutdownTimeoutSeconds());
        executor.initialize();
        return executor;
    }
}
