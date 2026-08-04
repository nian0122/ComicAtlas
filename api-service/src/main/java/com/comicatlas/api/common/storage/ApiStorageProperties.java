package com.comicatlas.api.common.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * API 侧存储属性 — 承载 storage.roots 配置。
 * API 对 STAGING 可写，对其他根只读。
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class ApiStorageProperties {
    /**
     * 存储根映射：key 为大写 rootKey（如 HQ、LQ、STAGING、TRASH、THUMBS、METADATA）。
     */
    private Map<String, ApiStorageRoot> roots;
}
