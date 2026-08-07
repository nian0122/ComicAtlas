package com.comicatlas.worker.config;

import com.comicatlas.common.mq.MqConsumerSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消费编排支持注册。
 * MqConsumerSupport 位于 comic-common 的 com.comicatlas.common.mq，不在 Worker 默认组件扫描范围内，
 * 故在此以 @Bean 方式注册（而非依赖 @Component 扫描）。
 */
@Configuration
public class MqConsumerSupportConfig {

    @Bean
    public MqConsumerSupport mqConsumerSupport() {
        return new MqConsumerSupport();
    }
}
