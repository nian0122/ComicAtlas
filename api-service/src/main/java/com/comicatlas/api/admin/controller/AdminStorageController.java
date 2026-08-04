package com.comicatlas.api.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageQueryService storageQueryService;
    private final MediaOperationCommandService mediaOperationCommandService;

    @GetMapping("/comics")
    public Result<Map<String, Object>> listComics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            ComicStorageQuery query) {
        List<ComicStorageDTO> records = storageQueryService.listComics(query, page, size);
        long total = storageQueryService.countComics(query);

        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("pages", (int) Math.ceil((double) total / size));
        result.put("current", page);
        return Result.ok(result);
    }

    @GetMapping("/comics/{comicId}/chapters")
    public Result<List<ChapterStorageDTO>> listChapters(@PathVariable Long comicId) {
        return Result.ok(storageQueryService.listChapters(comicId));
    }

    @GetMapping("/comics/{comicId}")
    public Result<ComicStorageDTO> getComic(@PathVariable Long comicId) {
        ComicStorageDTO dto = storageQueryService.getComic(comicId);
        if (dto == null) {
            return Result.fail(404, "漫画不存在");
        }
        return Result.ok(dto);
    }

    /**
     * 视频转码 — 统一任务管线入口。
     * 创建 ManagementTask（逐视频页 item）并返回 taskId，不再逐页发旧事件。
     */
    @PostMapping("/comics/{comicId}/transcode-videos")
    public Result<OperationSubmitResult> transcodeVideos(@PathVariable Long comicId) {
        log.info("请求漫画视频转码: comicId={}", comicId);
        return Result.ok(mediaOperationCommandService.requestTranscodeForComic(comicId));
    }
}
