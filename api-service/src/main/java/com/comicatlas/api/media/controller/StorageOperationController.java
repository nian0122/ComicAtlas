package com.comicatlas.api.media.controller;

import com.comicatlas.api.media.service.MediaOperationCommandService;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.media.service.HqDeleteOperationService;
import com.comicatlas.api.media.service.LqOperationService;
import com.comicatlas.api.media.service.TranscodeOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 存储操作统一入口（存储操作域）。
 * <p>
 * URL 形态：POST /api/storage/{operation}/{targetType}/{targetId}，targetType = comics | chapters。
 * 包含媒体存储操作端点：LQ 生成、HQ 删除（保留 LQ）、视频转码和刷新元数据。
 * 存储统计端点见 {@link StorageStatsController}。
 */
@RestController
@RequestMapping("/api/manage/storage")
@RequiredArgsConstructor
public class StorageOperationController {

    private final LqOperationService lqOperationService;
    private final HqDeleteOperationService hqDeleteOperationService;
    private final TranscodeOperationService transcodeOperationService;
    private final MediaOperationCommandService commandService;

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

    /** 对单个视频媒体发起转码。 */
    @PostMapping("/transcode/media/{mediaId}")
    public Result<OperationSubmitResultDTO> transcodeMedia(@PathVariable Long mediaId) {
        return Result.ok(transcodeOperationService.transcodeForMedia(mediaId));
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
     * 刷新漫画元数据：重读 HQ 目录 → 快照合并 DB（异步执行，经 MQ 回写）。
     * <p>
     * 委托 {@link MediaOperationCommandService#requestMetadataRefresh} 走统一命令管线：
     * 同事务 CAS 漫画 READY→REFRESHING、创建 COMIC 级任务并发布命令到 Outbox；
     * 漫画不存在 404、非 READY 或并发被占用 409。
     *
     * @param comicId 漫画 ID
     * @return 202 Accepted + 操作提交结果
     */
    @PostMapping("/refresh-metadata/comics/{comicId}")
    public ResponseEntity<Result<OperationSubmitResultDTO>> refreshMetadata(@PathVariable Long comicId) {
        OperationSubmitResultDTO dto = commandService.requestMetadataRefresh(comicId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(dto));
    }

}
