package com.comicatlas.api.exporter.controller;
import com.comicatlas.api.exporter.dto.ExportArtifactVO;
import com.comicatlas.api.exporter.dto.ExportTaskVO;
import com.comicatlas.api.exporter.model.ExportDirectoryOpenResult;
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

/**
 * 导出业务 HTTP 适配器，保留原有 {@code /api/manage/storage/export} URL 契约。
 * 创建接口只登记异步任务并返回 202；任务状态和产物由后续导出事件及查询接口反映。
 */
@RestController
@RequestMapping("/api/manage/storage/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportOperationService exportOperationService;
    private final ExportDirectoryService exportDirectoryService;

    /**
     * 创建漫画导出任务。
     *
     * @param comicId 待导出的漫画 ID
     * @param format 导出格式，省略时使用 ZIP
     * @return 已接受的异步导出任务
     */
    @PostMapping("/comics/{comicId}")
    public ResponseEntity<ExportTaskVO> createExport(@PathVariable Long comicId,
            @RequestParam(defaultValue = "ZIP") String format) {
        ExportTaskVO task = "ZIP".equalsIgnoreCase(format)
                ? exportOperationService.createExportTask(comicId)
                : exportOperationService.createExportTask(comicId, format);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    /** @param comicId 漫画 ID @return 该漫画的导出任务列表 */
    @GetMapping("/comics/{comicId}/tasks")
    public Result<List<ExportTaskVO>> listExports(@PathVariable Long comicId) {
        return Result.ok(exportOperationService.listExports(comicId));
    }

    /** @return 全部导出任务 */
    @GetMapping("/tasks")
    public Result<List<ExportTaskVO>> listAllExports() {
        return Result.ok(exportOperationService.listAllExports());
    }

    /** @param taskId 导出任务 ID @return 任务当前状态 */
    @GetMapping("/tasks/{taskId}")
    public Result<ExportTaskVO> getExportTask(@PathVariable Long taskId) {
        return Result.ok(exportOperationService.getTask(taskId));
    }

    /** @param taskId 导出任务 ID @return 任务已登记的导出产物 */
    @GetMapping("/tasks/{taskId}/artifacts")
    public Result<List<ExportArtifactVO>> getExportArtifacts(@PathVariable Long taskId) {
        return Result.ok(exportOperationService.listArtifacts(taskId));
    }

    /**
     * 请求在宿主机打开已生成的导出目录；目录不存在返回 404，当前环境不支持打开时返回 501。
     *
     * @param taskId 导出任务 ID
     * @return 打开结果对应的 HTTP 响应
     */
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
