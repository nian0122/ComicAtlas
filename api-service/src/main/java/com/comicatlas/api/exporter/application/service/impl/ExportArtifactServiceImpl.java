package com.comicatlas.api.exporter.application.service.impl;

import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.exporter.domain.model.ExportTaskStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.storage.PathTraversalException;
import com.comicatlas.api.exporter.application.port.out.ExportPersistencePort;
import com.comicatlas.api.exporter.interfaces.rest.dto.ExportArtifactVO;
import com.comicatlas.api.exporter.application.service.ExportZipVolumeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出产物分卷清单服务 — 读取 SUCCESS 导出任务的 outputPath，在 EXPORT 根内解析主 .zip，
 * 委托 {@link ExportZipVolumeResolver} 做卷发现与安全校验，返回有序分卷元数据。
 * <p>
 * 只返回元数据（卷名/大小/本地路径），不提供任何文件字节下载。
 * 异常消息与日志均不含物理路径（日志脱敏）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportArtifactServiceImpl implements com.comicatlas.api.exporter.application.port.in.ExportArtifactService {
    // 导出产物契约由应用服务公开，具体实现保持在导出业务包内。

    private static final String DEFAULT_OUTPUT_ROOT = "EXPORT";

    private final ExportPersistencePort persistencePort;
    private final ApiStorageProperties storageProperties;
    private final ExportZipVolumeResolver volumeResolver;

    /**
     * 返回指定导出任务的分卷清单（1-based 顺序，最后一个为 .zip 主卷）。
     *
     * @param taskId 导出任务 ID
     * @return 有序分卷元数据列表
     * @throws BusinessException 任务不存在(404)、任务未完成/路径非法/卷校验失败/大小漂移(409)
     */
    public List<ExportArtifactVO> listArtifacts(Long taskId) {
        ExportPersistencePort.ExportTaskSnapshot task = persistencePort.findTask(taskId);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "导出任务不存在: taskId=" + taskId);
        }
        if (task.status() != ExportTaskStatus.SUCCESS) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "导出任务未完成，无法提供产物清单，当前状态: " + task.status());
        }
        String outputPath = task.outputPath();
        if (outputPath == null || outputPath.isBlank()) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "导出任务缺少产物路径: taskId=" + taskId);
        }

        Path mainZip = resolveMainZip(task, outputPath, taskId);
        if (!Files.exists(mainZip, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "导出产物缺失: taskId=" + taskId);
        }

        List<Path> volumes = resolveVolumes(mainZip, taskId);

        List<ExportArtifactVO> artifacts = new ArrayList<>(volumes.size());
        long totalSize = 0;
        for (int volumeIndex = 0; volumeIndex < volumes.size(); volumeIndex++) {
            Path volume = volumes.get(volumeIndex);
            long size = fileSize(volume, taskId);
            totalSize += size;
            ExportArtifactVO artifactView = new ExportArtifactVO();
            artifactView.setIndex(volumeIndex + 1);
            artifactView.setFileName(volume.getFileName().toString());
            artifactView.setSize(size);
            artifactView.setLastSegment(volumeIndex == volumes.size() - 1);
            artifactView.setPhysicalPath(volume.toAbsolutePath().normalize().toString());
            artifacts.add(artifactView);
        }

        Long expectedSize = task.outputSize();
        if (expectedSize != null && !expectedSize.equals(totalSize)) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "导出产物大小不一致: taskId=" + taskId + ", 期望=" + expectedSize + ", 实际=" + totalSize);
        }
        return List.copyOf(artifacts);
    }

    private Path resolveMainZip(ExportPersistencePort.ExportTaskSnapshot task, String outputPath, Long taskId) {
        String rootKey = task.outputRoot() != null && !task.outputRoot().isBlank()
                ? task.outputRoot() : DEFAULT_OUTPUT_ROOT;
        try {
            return storageProperties.root(rootKey).resolve(outputPath);
        } catch (PathTraversalException e) {
            log.warn("导出产物路径穿越被拒绝: taskId={}", taskId);
            throw new BusinessException(HttpStatusCodes.CONFLICT, "导出产物路径非法");
        }
    }

    private List<Path> resolveVolumes(Path mainZip, Long taskId) {
        try {
            return volumeResolver.resolve(mainZip);
        } catch (IllegalArgumentException e) {
            log.warn("导出产物卷校验失败: taskId={}, reason={}", taskId, e.getMessage());
            throw new BusinessException(HttpStatusCodes.CONFLICT, "导出产物校验失败: " + e.getMessage());
        } catch (IOException e) {
            log.warn("读取导出产物失败: taskId={}, error={}", taskId, e.getMessage());
            throw new BusinessException(HttpStatusCodes.CONFLICT, "读取导出产物失败");
        }
    }

    private long fileSize(Path volume, Long taskId) {
        try {
            return Files.size(volume);
        } catch (IOException e) {
            log.warn("读取分卷大小失败: taskId={}, file={}", taskId, volume.getFileName());
            throw new BusinessException(HttpStatusCodes.CONFLICT, "读取分卷大小失败: " + volume.getFileName());
        }
    }
}
