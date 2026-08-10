package com.comicatlas.api.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import com.comicatlas.api.comic.dto.BatchComicUpdateDTO;
import com.comicatlas.api.comic.dto.BatchUpdateResultVO;
import com.comicatlas.api.comic.dto.ComicDetailVO;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.CreateComicRequest;
import com.comicatlas.api.comic.dto.UpdateComicRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 漫画 CRUD 与辅助端点。
 * <p>
 * 基路径 {@code /api}，提供漫画分页列表、详情、创建、乐观锁更新与删除，
 * 以及元数据、标签、批量更新和标题自动补全等管理端点。
 * 删除漫画仅创建回收任务异步回收文件（软删除语义），更新走 {@code version} 乐观锁。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ComicController {

    private final ComicService comicService;

    /**
     * 分页查询漫画列表。
     *
     * @param query 分页与筛选条件（标题/状态/分类/排序等）
     * @return 漫画分页数据（列表 VO）
     */
    @GetMapping("/comics")
    public Result<IPage<ComicListVO>> listComics(ComicListQuery query) {
        return Result.ok(comicService.listComics(query));
    }

    /**
     * 创建空漫画（初始 DRAFT，尚未导入文件）。
     *
     * @param request 漫画初始信息（标题/作者等）
     * @return 新创建的漫画详情
     */
    @PostMapping("/comics")
    public Result<ComicDetailVO> createComic(@Valid @RequestBody CreateComicRequest request) {
        return Result.ok(comicService.createComic(request));
    }

    /**
     * 乐观锁更新漫画基本信息，{@code version} 冲突时返回 409。
     *
     * @param request 待更新字段（仅更新非空项）
     * @return 更新后的漫画详情
     */
    @PutMapping("/comics/{id}")
    public Result<ComicDetailVO> updateComic(
            @PathVariable Long id,
            @Valid @RequestBody UpdateComicRequest request) {
        return Result.ok(comicService.updateComic(id, request));
    }

    /**
     * 查询漫画详情。
     *
     * @return 漫画详情（含章节/元数据等）
     */
    @GetMapping("/comics/{id}")
    public Result<ComicDetailVO> getComic(@PathVariable Long id) {
        return Result.ok(comicService.getComicDetail(id));
    }

    /**
     * 删除漫画：创建回收任务异步回收文件而非硬删。
     *
     * @param idempotencyKey 幂等键（可选），重复请求返回同一管理任务
     * @return 回收管理任务（可查询任务进度）
     */
    @DeleteMapping("/comics/{id}")
    public Result<ManagementTaskResponse> deleteComic(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.ok(comicService.deleteComic(id, idempotencyKey));
    }

    /**
     * 查询漫画元数据。
     *
     * @return 元数据（标题/作者/描述等可编辑字段）
     */
    @GetMapping("/comics/{id}/metadata")
    public Result<ComicMetadataDTO> getMetadata(@PathVariable Long id) {
        return Result.ok(comicService.getMetadata(id));
    }

    /**
     * 查询漫画绑定的标签 ID 列表。
     *
     * @return 标签 ID 列表
     */
    @GetMapping("/comics/{id}/tags")
    public Result<List<Long>> getComicTags(@PathVariable Long id) {
        return Result.ok(comicService.getComicTags(id));
    }

    /**
     * 批量更新漫画（分类/标签），跨页一次性生效。
     *
     * @param dto 批量更新内容（categoryId 与 addTagIds 至少提供一项，否则 400）
     * @return 批量更新结果（成功/失败明细）
     */
    @PostMapping("/comics/batch/update")
    public Result<BatchUpdateResultVO> batchUpdate(@Valid @RequestBody BatchComicUpdateDTO dto) {
        if (dto.getCategoryId() == null && (dto.getAddTagIds() == null || dto.getAddTagIds().isEmpty())) {
            return Result.fail(HttpStatusCodes.BAD_REQUEST, "至少需要提供 categoryId 或 addTagIds");
        }
        return Result.ok(comicService.batchUpdate(dto));
    }

    /**
     * 按关键字自动补全漫画标题。
     *
     * @param keyword 标题关键字
     * @return 匹配的标题列表
     */
    @GetMapping("/comics/autocomplete")
    public Result<List<String>> autocompleteTitles(@RequestParam String keyword) {
        return Result.ok(comicService.autocompleteTitles(keyword));
    }

}
