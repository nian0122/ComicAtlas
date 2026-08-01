package com.comicatlas.api.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.Result;
import com.comicatlas.common.event.VideoTranscodeRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageQueryService storageQueryService;
    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final RabbitTemplate rabbitTemplate;

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

    @PostMapping("/comics/{comicId}/transcode-videos")
    @Transactional
    public Result<Map<String, Object>> transcodeVideos(@PathVariable Long comicId) {
        // 获取漫画下所有章节 ID
        var chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));

        if (chapters.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("comicId", comicId);
            result.put("totalVideoPages", 0);
            result.put("notNeededCount", 0);
            result.put("submittedCount", 0);
            result.put("pendingCount", 0);
            result.put("doneCount", 0);
            result.put("failedCount", 0);
            return Result.ok(result);
        }

        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();

        // 查询所有 VIDEO 页面
        var allVideoPages = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .eq(Media::getMediaType, "VIDEO"));

        List<Media> toTranscode = new ArrayList<>();

        for (Media p : allVideoPages) {
            String status = p.getTranscodeStatus();
            if (!"DONE".equals(status) && !"PENDING".equals(status)) {
                String container = p.getContainer();
                if (container == null || (!"mp4".equals(container) && !"webm".equals(container))) {
                    toTranscode.add(p);
                }
            }
        }

        // 乐观锁标记 PENDING：仅当 transcode_status IN ('NOT_NEEDED','FAILED')
        int submittedCount = 0;
        List<Media> pendingPages = new ArrayList<>();

        for (Media p : toTranscode) {
            int updated = mediaMapper.update(null,
                    new LambdaUpdateWrapper<Media>()
                            .eq(Media::getId, p.getId())
                            .in(Media::getTranscodeStatus, "NOT_NEEDED", "FAILED")
                            .set(Media::getTranscodeStatus, "PENDING"));
            if (updated > 0) {
                submittedCount++;
                p.setTranscodeStatus("PENDING");
                pendingPages.add(p);
            }
        }

        // 事务提交后逐页发送 MQ（Metis G1：一页一条消息，非批量）
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (Media p : pendingPages) {
                            rabbitTemplate.convertAndSend("comic.video", "video.transcode.requested",
                                    new VideoTranscodeRequestedEvent(
                                            UUID.randomUUID(), Instant.now(), comicId,
                                            p.getId(), p.getHqRoot(), p.getHqPath(), p.getContainer()));
                        }
                    }
                });

        int notNeededCount = 0;
        int pendingCount = 0;
        int doneCount = 0;
        int failedCount = 0;
        for (Media p : allVideoPages) {
            switch (p.getTranscodeStatus()) {
                case "NOT_NEEDED" -> notNeededCount++;
                case "PENDING" -> pendingCount++;
                case "DONE" -> doneCount++;
                case "FAILED" -> failedCount++;
                default -> failedCount++;
            }
        }

        log.info("视频转码任务已发布: comicId={}, submitted={}, pending={}, done={}, failed={}",
                comicId, submittedCount, pendingCount, doneCount, failedCount);

        Map<String, Object> result = new HashMap<>();
        result.put("comicId", comicId);
        result.put("totalVideoPages", allVideoPages.size());
        result.put("notNeededCount", notNeededCount);
        result.put("submittedCount", submittedCount);
        result.put("pendingCount", pendingCount);
        result.put("doneCount", doneCount);
        result.put("failedCount", failedCount);
        return Result.ok(result);
    }
}
