package com.comicatlas.api.recovery.trash;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.api.task.mapper.TrashManifestMapper;
import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
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
 * 架构边界：API 只操作数据库，<b>不写</b>本地文件。
 * manifest 存 {@code trash_manifest} 表（API 写，Worker 只读 DB 后按清单移动文件）；
 * actual.json 保持文件形式，由 Worker 写（操作文件）、API 以只读挂载访问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashManifestService {

    private static final String ACTUAL_FILE = "actual.json";

    private final TrashManifestMapper trashManifestMapper;
    private final ApiStorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    /** 清单目录：TRASH/{targetType}/{targetId}/{taskId}（actual.json 所在目录，Worker 操作文件） */
    public Path manifestDir(String targetType, Long targetId, Long taskId) {
        if (taskId == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "缺少 TRASH 清单任务 ID");
        }
        ApiStorageRoot trash = storageProperties.getRoots().get("TRASH");
        if (trash == null || !trash.isEnabled()) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "TRASH 存储根未配置");
        }
        return trash.resolve(targetType + "/" + targetId + "/" + taskId);
    }

    /** 写入不可变 manifest 到 DB（幂等：同 taskId 覆盖为同一内容由调用方保证） */
    public TrashManifestDTO writeManifest(TrashManifestDTO manifest) {
        TrashManifestRecord record = new TrashManifestRecord();
        record.setTaskId(manifest.taskId());
        record.setTargetType(manifest.targetType());
        record.setTargetId(manifest.targetId());
        record.setManifestJson(toJson(manifest));
        trashManifestMapper.insert(record);
        log.info("写入 TRASH 清单(DB): taskId={}", manifest.taskId());
        return manifest;
    }

    /** 从 DB 读 manifest（不存在返回 null） */
    public TrashManifestDTO readManifest(String targetType, Long targetId, Long taskId) {
        TrashManifestRecord record = trashManifestMapper.selectById(taskId);
        if (record == null) {
            return null;
        }
        try {
            return objectMapper.readValue(record.getManifestJson(), TrashManifestDTO.class);
        } catch (Exception e) {
            log.warn("读取 TRASH 清单(DB)失败: taskId={}", taskId, e);
            return null;
        }
    }

    /** 从 DB 读指定目标最近一次清单（对账/恢复定位用，不存在返回 null） */
    public TrashManifestDTO readLatestManifest(String targetType, Long targetId) {
        TrashManifestRecord record = trashManifestMapper.selectOne(
                new LambdaQueryWrapper<TrashManifestRecord>()
                        .eq(TrashManifestRecord::getTargetType, targetType)
                        .eq(TrashManifestRecord::getTargetId, targetId)
                        .orderByDesc(TrashManifestRecord::getTaskId)
                        .last("LIMIT 1"));
        if (record == null) {
            return null;
        }
        try {
            return objectMapper.readValue(record.getManifestJson(), TrashManifestDTO.class);
        } catch (Exception e) {
            log.warn("读取 TRASH 清单(DB)失败: targetType={}, targetId={}", targetType, targetId, e);
            return null;
        }
    }

    /** 读 actual.json（Worker 写的文件，API 只读） */
    public TrashManifestItemDTO readActual(String targetType, Long targetId, Long taskId) {
        Path file = manifestDir(targetType, targetId, taskId).resolve(ACTUAL_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TrashManifestItemDTO.class);
        } catch (Exception e) {
            log.warn("读取 TRASH 实际结果失败: {}", file, e);
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (IOException e) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "TRASH 清单序列化失败: " + e.getMessage());
        }
    }
}
