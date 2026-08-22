package com.comicatlas.api.task.batch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 批量操作配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "comic.batch")
public class BatchProperties {

    /** 批量快照物化上限（默认 10000，可配置） */
    private int maxItems = 10000;

    /** preview token 有效期秒数（默认 300s = 5 分钟） */
    private int previewTtlSeconds = 300;
}
