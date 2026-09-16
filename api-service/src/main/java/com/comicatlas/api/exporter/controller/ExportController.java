package com.comicatlas.api.exporter.controller;

import com.comicatlas.api.exporter.dto.ExportArtifactVO;
import com.comicatlas.api.exporter.dto.ExportTaskVO;
import com.comicatlas.api.exporter.service.ExportDirectoryOpenResult;
import com.comicatlas.api.exporter.service.ExportDirectoryService;
import com.comicatlas.api.exporter.service.ExportOperationService;
import com.comicatlas.contract.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 导出业务 HTTP 适配器，保留原有 /api/manage/storage/export URL 契约。 */
@RestController
@RequestMapping("/api/manage/storage/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportOperationService exportOperationService;
    private final ExportDirectoryService exportDirectoryService;

    @PostMapping("/comics/{comicId}")
    public ResponseEntity<ExportTaskVO> createExport(@PathVariable Long comicId,
            @RequestParam(defaultValue = "ZIP") String format) {
        ExportTaskVO task = "ZIP".equalsIgnoreCase(format)
                ? exportOperationService.createExportTask(comicId)
                : exportOperationService.createExportTask(comicId, format);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    @GetMapping("/comics/{comicId}/tasks")
    public Result<List<ExportTaskVO>> listExports(@PathVariable Long comicId) {
        return Result.ok(exportOperationService.listExports(comicId));
    }

    @GetMapping("/tasks")
    public Result<List<ExportTaskVO>> listAllExports() {
        return Result.ok(exportOperationService.listAllExports());
    }

    @GetMapping("/tasks/{taskId}")
    public Result<ExportTaskVO> getExportTask(@PathVariable Long taskId) {
        return Result.ok(exportOperationService.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    public Result<List<ExportArtifactVO>> getExportArtifacts(@PathVariable Long taskId) {
        return Result.ok(exportOperationService.listArtifacts(taskId));
    }

    @PostMapping("/tasks/{taskId}/open")
    public ResponseEntity<?> openExportDir(@PathVariable Long taskId) {
        ExportDirectoryOpenResult result = exportDirectoryService.open(taskId);
        if (result.status() == ExportDirectoryOpenResult.Status.OPENED) {
            return ResponseEntity.ok().build();
        }
        if (result.status() == ExportDirectoryOpenResult.Status.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(result.message());
    }
}
