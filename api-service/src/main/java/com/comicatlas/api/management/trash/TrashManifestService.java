package com.comicatlas.api.management.trash;

import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.common.dto.TrashManifest;
import com.comicatlas.common.dto.TrashManifestActual;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TRASH 资产清单服务（API 侧）。
 * <p>
 * API 基于 DB refs 创建不可变 manifest.json，Worker 严格按清单移动文件；
 * actual.json 记录 Worker 实际执行结果，用于补偿判断与对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashManifestService {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String ACTUAL_FILE = "actual.json";

    private final ApiStorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    /** 清单目录：TRASH/{targetType}/{targetId}/{taskId} */
    public Path manifestDir(String targetType, Long targetId, Long taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "缺少 TRASH 清单任务 ID");
        }
        ApiStorageRoot trash = storageProperties.getRoots().get("TRASH");
        if (trash == null || !trash.isEnabled()) {
            throw new BusinessException(500, "TRASH 存储根未配置");
        }
        return trash.resolve(targetType + "/" + targetId + "/" + taskId);
    }

    /** 写入不可变 manifest.json（幂等：已存在则覆盖为同一内容由调用方保证） */
    public TrashManifest writeManifest(TrashManifest manifest) {
        Path dir = manifestDir(manifest.targetType(), manifest.targetId(), manifest.taskId());
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(MANIFEST_FILE);
            Files.writeString(file, toJson(manifest), StandardCharsets.UTF_8);
            log.info("写入 TRASH 清单: {}", file);
            return manifest;
        } catch (IOException e) {
            throw new BusinessException(500, "写入 TRASH 清单失败: " + e.getMessage());
        }
    }

    /** 读取 manifest.json（不存在返回 null） */
    public TrashManifest readManifest(String targetType, Long targetId, Long taskId) {
        Path file = manifestDir(targetType, targetId, taskId).resolve(MANIFEST_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TrashManifest.class);
        } catch (Exception e) {
            log.warn("读取 TRASH 清单失败: {}", file, e);
            return null;
        }
    }

    /** 读取 actual.json（不存在返回 null） */
    public TrashManifestActual readActual(String targetType, Long targetId, Long taskId) {
        Path file = manifestDir(targetType, targetId, taskId).resolve(ACTUAL_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TrashManifestActual.class);
        } catch (Exception e) {
            log.warn("读取 TRASH 实际结果失败: {}", file, e);
            return null;
        }
    }

    /** 写入 actual.json（Worker 之外仅对账修复时使用） */
    public void writeActual(TrashManifestActual actual) {
        Path dir = manifestDir(actual.targetType(), actual.targetId(), actual.taskId());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ACTUAL_FILE), toJson(actual), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(500, "写入 TRASH 实际结果失败: " + e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(500, "TRASH 清单序列化失败: " + e.getMessage());
        }
    }
}
