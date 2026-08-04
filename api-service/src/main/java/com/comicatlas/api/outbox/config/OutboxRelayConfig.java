package com.comicatlas.api.outbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox Relay 调度配置。
 */
@Configuration
@EnableScheduling
public class OutboxRelayConfig {
    // 配置仅激活 @EnableScheduling，relay 由 OutboxRelay 的 @Scheduled 驱动。
}
