package com.comicatlas.api.exporter.service;

import com.comicatlas.api.exporter.dto.ExportTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 导出产物所在目录的本机打开适配器。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportDirectoryService {
    // TODO(LAYER-14): Service 功能契约与具体实现未分离；应抽取 service 接口，并将实现迁移到 service/impl。
    private final ExportOperationService exportOperationService;

    public ExportDirectoryOpenResult open(Long taskId) {
        ExportTaskVO task = exportOperationService.getTask(taskId);
        String physicalPath = task.getPhysicalPath();
        if (physicalPath == null) {
            return new ExportDirectoryOpenResult(ExportDirectoryOpenResult.Status.NOT_FOUND, null);
        }
        Path directory = Path.of(physicalPath.replace("/", java.io.File.separator)).getParent();
        if (directory == null || !Files.exists(directory)) {
            return new ExportDirectoryOpenResult(ExportDirectoryOpenResult.Status.NOT_FOUND, null);
        }
        if (!Desktop.isDesktopSupported()) {
            return unsupported(directory);
        }
        try {
            Desktop.getDesktop().open(directory.toFile());
            return new ExportDirectoryOpenResult(ExportDirectoryOpenResult.Status.OPENED, null);
        } catch (IOException exception) {
            log.warn("打开导出目录失败: taskId={}, error={}", taskId, exception.getMessage(), exception);
            return unsupported(directory);
        }
    }

    private ExportDirectoryOpenResult unsupported(Path directory) {
        return new ExportDirectoryOpenResult(ExportDirectoryOpenResult.Status.NOT_SUPPORTED,
                "无法打开文件资源管理器，目录: " + directory);
    }
}
