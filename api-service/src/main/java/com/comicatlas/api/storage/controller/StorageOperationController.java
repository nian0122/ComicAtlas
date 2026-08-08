package com.comicatlas.api.storage.controller;

import com.comicatlas.api.admin.dto.RefreshMetadataResultDTO;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.storage.service.ExportOperationService;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import com.comicatlas.api.storage.service.LqOperationService;
import com.comicatlas.api.storage.service.MetadataRefreshService;
import com.comicatlas.api.storage.service.TranscodeOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 存储操作统一入口（存储操作域）。
 * <p>
 * URL 形态：POST /api/storage/{operation}/{targetType}/{targetId}，targetType = comics | chapters。
 * 包含全部存储操作端点：LQ 生成、HQ 删除（保留 LQ）、视频转码、刷新元数据、导出及导出文件的下载/打开。
 * 存储统计端点见 {@link StorageStatsController}。
 */
@Slf4j
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageOperationController {

    private final LqOperationService lqOperationService;
    private final HqDeleteOperationService hqDeleteOperationService;
    private final TranscodeOperationService transcodeOperationService;
    private final MetadataRefreshService metadataRefreshService;
    private final ExportOperationService exportOperationService;

    // ======================== LQ 生成 ========================

    /**
     * 为整本漫画生成 LQ 版本（异步执行，生成结果经 MQ 回写）。
     *
     * @param comicId 漫画 ID
     * @param regenerate 是否强制重新生成（忽略已存在的 LQ 结果）
     * @return 操作提交结果
     */
    @PostMapping("/lq/comics/{comicId}")
    public Result<OperationSubmitResultDTO> generateComicLq(
            @PathVariable Long comicId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForComic(comicId, regenerate));
    }

    /**
     * 为单个章节生成 LQ 版本（异步执行，生成结果经 MQ 回写）。
     *
     * @param chapterId 章节 ID
     * @param regenerate 是否强制重新生成（忽略已存在的 LQ 结果）
     * @return 操作提交结果
     */
    @PostMapping("/lq/chapters/{chapterId}")
    public Result<OperationSubmitResultDTO> generateChapterLq(
            @PathVariable Long chapterId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqOperationService.generateForChapter(chapterId, regenerate));
    }

    // ======================== HQ 删除（保留 LQ） ========================

    /**
     * 删除整本漫画的 HQ 原图（保留 LQ 版本，异步执行）。
     *
     * @param comicId 漫画 ID
     * @return 操作提交结果
     */
    @PostMapping("/delete-hq/comics/{comicId}")
    public Result<OperationSubmitResultDTO> deleteComicHq(@PathVariable Long comicId) {
        return Result.ok(hqDeleteOperationService.deleteForComic(comicId));
    }

    /**
     * 删除单个章节的 HQ 原图（保留 LQ 版本，异步执行）。
     *
     * @param chapterId 章节 ID
     * @return 操作提交结果
     */
    @PostMapping("/delete-hq/chapters/{chapterId}")
    public Result<OperationSubmitResultDTO> deleteChapterHq(@PathVariable Long chapterId) {
        return Result.ok(hqDeleteOperationService.deleteForChapter(chapterId));
    }

    // ======================== 视频转码 ========================

    /**
     * 对整本漫画的章节视频发起转码（异步执行）。
     *
     * @param comicId 漫画 ID
     * @return 操作提交结果
     */
    @PostMapping("/transcode/comics/{comicId}")
    public Result<OperationSubmitResultDTO> transcodeComic(@PathVariable Long comicId) {
        return Result.ok(transcodeOperationService.transcodeForComic(comicId));
    }

    /**
     * 对单个章节的视频发起转码（异步执行）。
     *
     * @param chapterId 章节 ID
     * @return 操作提交结果
     */
    @PostMapping("/transcode/chapters/{chapterId}")
    public Result<OperationSubmitResultDTO> transcodeChapter(@PathVariable Long chapterId) {
        return Result.ok(transcodeOperationService.transcodeForChapter(chapterId));
    }

    // ======================== 刷新元数据 ========================

    /**
     * 刷新漫画元数据：重读 HQ 目录重建 metadata.json 及目录/章节信息。
     *
     * @param comicId 漫画 ID
     * @return 刷新结果（章节/页面统计）
     */
    @PostMapping("/refresh-metadata/comics/{comicId}")
    public Result<RefreshMetadataResultDTO> refreshMetadata(@PathVariable Long comicId) {
        return Result.ok(metadataRefreshService.refresh(comicId));
    }

    // ======================== 导出 ========================

    /**
     * 为漫画创建导出任务（异步打包，任务就绪后经 MQ 通知）。
     *
     * @param comicId 漫画 ID
     * @return 202 Accepted + 导出任务信息
     */
    @PostMapping("/export/comics/{comicId}")
    public ResponseEntity<ExportTaskVO> createExport(@PathVariable Long comicId) {
        ExportTaskVO task = exportOperationService.createExportTask(comicId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    /**
     * 查询漫画的导出任务列表。
     *
     * @param comicId 漫画 ID
     * @return 导出任务列表
     */
    @GetMapping("/export/comics/{comicId}/tasks")
    public Result<List<ExportTaskVO>> listExports(@PathVariable Long comicId) {
        return Result.ok(exportOperationService.listExports(comicId));
    }

    /**
     * 查询导出任务详情（含导出产物物理路径）。
     *
     * @param taskId 导出任务 ID
     * @return 导出任务详情
     */
    @GetMapping("/export/tasks/{taskId}")
    public Result<ExportTaskVO> getExportTask(@PathVariable Long taskId) {
        return Result.ok(exportOperationService.getTask(taskId));
    }

    /**
     * 下载导出文件（流式）。
     */
    @GetMapping("/export/tasks/{taskId}/download")
    public ResponseEntity<StreamingResponseBody> downloadExport(@PathVariable Long taskId) {
        ExportTaskVO task = exportOperationService.getTask(taskId);
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
     * 打开导出文件所在目录（Windows/Linux/macOS 通用，Desktop API；失败回退 501）。
     */
    @PostMapping("/export/tasks/{taskId}/open")
    public ResponseEntity<?> openExportDir(@PathVariable Long taskId) {
        ExportTaskVO task = exportOperationService.getTask(taskId);
        String physicalPath = task.getPhysicalPath();
        if (physicalPath == null) {
            return ResponseEntity.notFound().build();
        }
        Path dirPath = Path.of(physicalPath.replace("/", java.io.File.separator)).getParent();
        if (dirPath == null || !Files.exists(dirPath)) {
            return ResponseEntity.notFound().build();
        }
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(dirPath.toFile());
                return ResponseEntity.ok().build();
            } catch (IOException e) {
                log.warn("Desktop.open 失败: dir={}, error={}", dirPath, e.getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body("无法打开文件资源管理器，目录: " + dirPath);
    }
}
