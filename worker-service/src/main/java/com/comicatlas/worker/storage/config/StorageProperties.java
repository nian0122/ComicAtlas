package com.comicatlas.worker.storage.config;

import com.comicatlas.worker.storage.StorageRoot;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Worker 存储根配置的业务归属模型。配置前缀保持为 {@code storage}。 */
@Data
public class StorageProperties {
    private Map<String, StorageRoot> roots = new LinkedHashMap<>();
}
