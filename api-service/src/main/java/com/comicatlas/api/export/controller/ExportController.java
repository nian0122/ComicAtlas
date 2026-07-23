package com.comicatlas.api.export.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.export.service.ExportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /**
     * POST /api/comics/{comicId}/export — 创建导出任务
     */
    @PostMapping("/comics/{comicId}/export")
    public ResponseEntity<ExportTaskVO> createExport(@PathVariable Long comicId) {
        ExportTaskVO task = exportService.createExportTask(comicId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    /**
     * GET /api/comics/{comicId}/exports — 列出漫画的导出任务
     */
    @GetMapping("/comics/{comicId}/exports")
    public Result<List<ExportTaskVO>> listExports(@PathVariable Long comicId) {
        return Result.ok(exportService.listExports(comicId));
    }

    /**
     * GET /api/export/{taskId} — 获取导出任务详情（含 physicalPath）
     */
    @GetMapping("/export/{taskId}")
    public Result<ExportTaskVO> getTask(@PathVariable Long taskId) {
        return Result.ok(exportService.getTask(taskId));
    }

    /**
     * GET /api/export/{taskId}/download — 下载导出文件
     */
    @GetMapping("/export/{taskId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long taskId) {
        ExportTaskVO task = exportService.getTask(taskId);

        String physicalPath = task.getPhysicalPath();
        if (physicalPath == null) {
            return ResponseEntity.notFound().build();
        }

        Path filePath = Path.of(physicalPath.replace("/", java.io.File.separator));
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        String filename = filePath.getFileName().toString();
        StreamingResponseBody stream = outputStream -> {
            try {
                Files.copy(filePath, outputStream);
                outputStream.flush();
            } catch (IOException e) {
                log.error("下载导出文件失败: taskId={}, path={}", taskId, physicalPath, e);
                throw new RuntimeException("下载失败: " + e.getMessage(), e);
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(stream);
    }

    /**
     * POST /api/export/{taskId}/open — 打开导出文件所在目录
     */
    @PostMapping("/export/{taskId}/open")
    public ResponseEntity<?> openDir(@PathVariable Long taskId) {
        ExportTaskVO task = exportService.getTask(taskId);

        String physicalPath = task.getPhysicalPath();
        if (physicalPath == null) {
            return ResponseEntity.notFound().build();
        }

        Path dirPath = Path.of(physicalPath.replace("/", java.io.File.separator)).getParent();
        if (dirPath == null || !Files.exists(dirPath)) {
            return ResponseEntity.notFound().build();
        }

        // Windows/Linux/macOS 通用：尝试用 Desktop API 打开目录
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(dirPath.toFile());
                return ResponseEntity.ok().build();
            } catch (IOException e) {
                log.warn("Desktop.open 失败: dir={}, error={}", dirPath, e.getMessage());
            }
        }

        // fallback: 返回 501 Not Implemented
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body("无法打开文件资源管理器，目录: " + dirPath);
    }
}
