package com.comicatlas.api.comic.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.ChapterCreateRequest;
import com.comicatlas.api.comic.dto.ChapterMoveRequest;
import com.comicatlas.api.comic.dto.ChapterRenameRequest;
import com.comicatlas.api.comic.dto.ChapterReorderRequest;
import com.comicatlas.api.comic.dto.ChapterVO;
import com.comicatlas.api.comic.service.ChapterManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 章节管理端点（create / rename / move / reorder / trash）。
 *
 * <p>所有请求携带 comicId，用于校验 path ID 属于同一漫画。
 */
@RestController
@RequestMapping("/api/comics/{comicId}/chapters")
@RequiredArgsConstructor
public class ChapterManagementController {

    private final ChapterManagementService chapterManagementService;

    @PostMapping
    public Result<ChapterVO> create(
            @PathVariable Long comicId,
            @Valid @RequestBody ChapterCreateRequest request) {
        return Result.ok(chapterManagementService.createChapter(comicId, request));
    }

    @PatchMapping("/{chapterId}")
    public Result<ChapterVO> rename(
            @PathVariable Long comicId,
            @PathVariable Long chapterId,
            @RequestBody ChapterRenameRequest request) {
        return Result.ok(chapterManagementService.renameChapter(comicId, chapterId, request));
    }

    @PutMapping("/{chapterId}/move")
    public Result<ChapterVO> move(
            @PathVariable Long comicId,
            @PathVariable Long chapterId,
            @RequestBody(required = false) ChapterMoveRequest request) {
        Long catalogId = request != null ? request.getCatalogId() : null;
        return Result.ok(chapterManagementService.moveChapter(comicId, chapterId, catalogId));
    }

    @PutMapping("/{chapterId}/reorder")
    public Result<ChapterVO> reorder(
            @PathVariable Long comicId,
            @PathVariable Long chapterId,
            @Valid @RequestBody ChapterReorderRequest request) {
        return Result.ok(chapterManagementService.reorderChapter(comicId, chapterId, request.getTargetGlobalOrder()));
    }

    @DeleteMapping("/{chapterId}")
    public Result<Void> trash(@PathVariable Long comicId, @PathVariable Long chapterId) {
        chapterManagementService.trashChapter(comicId, chapterId);
        return Result.ok();
    }
}
