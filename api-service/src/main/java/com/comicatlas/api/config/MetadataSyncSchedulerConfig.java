package com.comicatlas.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * metadata 更新协调调度器配置 — 为 {@code MetadataUpdateCoordinator} 的合并窗口提供专用调度线程。
 * <p>
 * 阿里规范：线程池必须明确核心/最大线程数、队列容量、拒绝策略与关闭方式。
 * <ul>
 *   <li>poolSize=1：metadata 同步是低频轻量操作，单线程避免并发调度抖动；</li>
 *   <li>底层 {@code ScheduledThreadPoolExecutor} 使用 JDK 内部 DelayedWorkQueue 作为调度队列
 *       （非业务任务队列，任务量受 {@code MetadataUpdateCoordinator} 按 comicId 合并限制，无堆积风险）；
 *       拒绝策略 {@code AbortPolicy}，异常由调度线程记录，绝不静默丢弃；</li>
 *   <li>destroyMethod=shutdown：Spring 容器关闭时优雅停止，等待在途任务完成。</li>
 * </ul>
 */
@Configuration
public class MetadataSyncSchedulerConfig {

    /** 调度线程名前缀：便于线程转储定位。 */
    private static final String THREAD_NAME_PREFIX = "metadata-sync-";

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler metadataSyncScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return scheduler;
    }
}
