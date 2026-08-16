package com.comicatlas.api.management.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.MediaOperationEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体操作资格查询 API。
 * <p>
 * 前端按钮所需状态（allowedOperations/blockedReasons）由此查询，
 * 前端不得自行复制操作矩阵。
 */
@RestController
@RequestMapping("/api/manage/operations")
@RequiredArgsConstructor
public class MediaOperationController {

    private final MediaOperationEligibilityService eligibilityService;

    /**
     * 查询漫画当前状态下允许执行的管理操作及被阻止原因。
     * 前端按钮态以此结果为准，不得自行复制操作矩阵。
     *
     * @param comicId 漫画 ID
     * @return 允许/阻止的操作结果
     */
    @GetMapping("/comics/{comicId}")
    public Result<AllowedOperations> forComic(@PathVariable Long comicId) {
        return Result.ok(eligibilityService.forComic(comicId));
    }

    /**
     * 查询章节当前状态下允许执行的管理操作及被阻止原因。
     *
     * @param chapterId 章节 ID
     * @return 允许/阻止的操作结果
     */
    @GetMapping("/chapters/{chapterId}")
    public Result<AllowedOperations> forChapter(@PathVariable Long chapterId) {
        return Result.ok(eligibilityService.forChapter(chapterId));
    }

    /**
     * 查询单个媒体当前状态下允许执行的管理操作及被阻止原因。
     *
     * @param mediaId 媒体 ID
     * @return 允许/阻止的操作结果
     */
    @GetMapping("/media/{mediaId}")
    public Result<AllowedOperations> forMedia(@PathVariable Long mediaId) {
        return Result.ok(eligibilityService.forMedia(mediaId));
    }
}
