package com.comicatlas.api.comic.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.comic.dto.ChapterCreateRequest;
import com.comicatlas.contract.comic.dto.ChapterMoveRequest;
import com.comicatlas.contract.comic.dto.ChapterRenameRequest;
import com.comicatlas.contract.comic.dto.ChapterReorderRequest;
import com.comicatlas.contract.comic.dto.ChapterVO;
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
@RequestMapping("/api/manage/comics/{comicId}/chapters")
@RequiredArgsConstructor
public class ChapterManagementController {

    private final ChapterManagementService chapterManagementService;

    /**
     * 在指定漫画下创建章节，自动分配 globalOrder（全书顺序）与目录内 sortOrder。
     *
     * @param request 章节信息（标题/编号/所属目录）
     * @return 创建的章节
     */
    @PostMapping
    public Result<ChapterVO> create(
            @PathVariable Long comicId,
            @Valid @RequestBody ChapterCreateRequest request) {
        return Result.ok(chapterManagementService.createChapter(comicId, request));
    }

    /**
     * 重命名章节（标题或编号，至少提供一项）。
     *
     * @param request 标题/编号
     * @return 重命名后的章节
     */
    @PatchMapping("/{chapterId}")
    public Result<ChapterVO> rename(
            @PathVariable Long comicId,
            @PathVariable Long chapterId,
            @RequestBody ChapterRenameRequest request) {
        return Result.ok(chapterManagementService.renameChapter(comicId, chapterId, request));
    }

    /**
     * 移动章节到目标目录（跨目录重排 sortOrder）。
     *
     * @param request 目标目录 ID；请求体缺省或 catalogId 为 null 表示移到根级
     * @return 移动后的章节
     */
    @PutMapping("/{chapterId}/move")
    public Result<ChapterVO> move(
            @PathVariable Long comicId,
            @PathVariable Long chapterId,
            @RequestBody(required = false) ChapterMoveRequest request) {
        Long catalogId = request != null ? request.getCatalogId() : null;
        return Result.ok(chapterManagementService.moveChapter(comicId, chapterId, catalogId));
    }

    /**
     * 全书章节重排（globalOrder 两阶段写库，避免唯一键瞬时冲突）。
     *
     * @param request 目标 globalOrder（1 基）
     * @return 重排后的章节
     */
    @PutMapping("/{chapterId}/reorder")
    public Result<ChapterVO> reorder(
            @PathVariable Long comicId,
            @PathVariable Long chapterId,
            @Valid @RequestBody ChapterReorderRequest request) {
        return Result.ok(chapterManagementService.reorderChapter(comicId, chapterId, request.getTargetGlobalOrder()));
    }

    /**
     * 回收章节（软删除，状态 → TRASHING，Worker 异步移入回收站）。
     *
     * @return 空结果
     */
    @DeleteMapping("/{chapterId}")
    public Result<Void> trash(@PathVariable Long comicId, @PathVariable Long chapterId) {
        chapterManagementService.trashChapter(comicId, chapterId);
        return Result.ok();
    }
}
