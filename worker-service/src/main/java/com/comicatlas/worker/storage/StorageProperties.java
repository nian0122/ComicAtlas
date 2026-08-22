package com.comicatlas.worker.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;

@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private Map<String, StorageRoot> roots = new LinkedHashMap<>();
}
