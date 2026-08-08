package com.comicatlas.worker.file.trash;

import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Worker 侧 TRASH 清单存取 — 读取 API 创建的不可变 manifest.json，回写 actual.json。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashManifestStore {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String ACTUAL_FILE = "actual.json";

    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    public StorageRoot trashRoot() {
        StorageRoot root = storageProperties.getRoots().get("TRASH");
        if (root == null || !root.isEnabled()) {
            throw new IllegalStateException("TRASH 存储根未配置");
        }
        return root;
    }

    public Path manifestDir(String targetType, Long targetId, Long taskId) {
        return trashRoot().resolve(targetType + "/" + targetId + "/" + taskId);
    }

    public TrashManifestDTO readManifest(String targetType, Long targetId, Long taskId) {
        Path file = manifestDir(targetType, targetId, taskId).resolve(MANIFEST_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TrashManifestDTO.class);
        } catch (Exception e) {
            log.warn("读取 TRASH 清单失败: {}", file, e);
            return null;
        }
    }

    public void writeActual(TrashManifestItemDTO actual) {
        Path dir = manifestDir(actual.targetType(), actual.targetId(), actual.taskId());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ACTUAL_FILE), objectMapper.writeValueAsString(actual),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入 actual.json 失败: {}", dir, e);
        }
    }
}
