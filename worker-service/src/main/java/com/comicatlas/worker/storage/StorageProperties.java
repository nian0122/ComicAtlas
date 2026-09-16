package com.comicatlas.worker.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @deprecated 请使用 {@link com.comicatlas.worker.storage.config.StorageProperties}。
 * 保留该 Bean 类型以兼容既有 Worker/API 测试与扩展代码。
 */
@Deprecated
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties extends com.comicatlas.worker.storage.config.StorageProperties {
}
