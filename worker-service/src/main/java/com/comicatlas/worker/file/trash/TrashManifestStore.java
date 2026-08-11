package com.comicatlas.worker.file.trash;

import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.worker.mapper.TrashManifestReadMapper;
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
 * Worker 侧 TRASH 清单存取 — 从 DB 只读 API 创建的不可变 manifest，回写 actual.json 文件。
 * <p>
 * 架构边界：Worker 只读数据库、操作本地文件。manifest 存 DB（只读查询），
 * actual.json 写文件（操作文件）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashManifestStore {

    private static final String ACTUAL_FILE = "actual.json";

    private final StorageProperties storageProperties;
    private final TrashManifestReadMapper trashManifestReadMapper;
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

    /** 从 DB 只读 manifest（无记录返回 null） */
    public TrashManifestDTO readManifest(String targetType, Long targetId, Long taskId) {
        String json = trashManifestReadMapper.selectManifestJsonByTaskId(taskId);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TrashManifestDTO.class);
        } catch (Exception e) {
            log.warn("解析 TRASH 清单(DB)失败: taskId={}", taskId, e);
            return null;
        }
    }

    /** 回写 actual.json（Worker 操作文件；API 以只读挂载访问） */
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
