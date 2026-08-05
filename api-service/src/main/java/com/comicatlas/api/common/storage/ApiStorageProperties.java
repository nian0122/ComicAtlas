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

    /**
     * 按 rootKey 获取存储根，未配置时抛出明确的启动期错误，
     * 避免调用方对 Map.get 空指针后难以排查。
     */
    public ApiStorageRoot root(String rootKey) {
        if (roots == null || !roots.containsKey(rootKey)) {
            throw new IllegalStateException("存储根未配置: " + rootKey);
        }
        return roots.get(rootKey);
    }
}
