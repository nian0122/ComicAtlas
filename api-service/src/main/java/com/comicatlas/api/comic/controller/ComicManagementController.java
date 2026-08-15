package com.comicatlas.api.comic.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.comic.dto.BatchUpdateResultVO;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.BatchComicUpdateRequest;
import com.comicatlas.api.comic.dto.ComicMetadataUpdateRequest;
import com.comicatlas.api.comic.dto.ComicTagUpdateRequest;
import com.comicatlas.api.comic.dto.CreateComicRequest;
import com.comicatlas.api.comic.dto.UpdateComicRequest;
import com.comicatlas.api.comic.service.ComicManagementService;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 漫画管理接口（管理域）。
 * <p>
 * 基路径 {@code /api/manage/comics}，提供漫画创建、乐观锁更新与删除，
 * 以及元数据、标签、批量更新等管理写端点。漫画查询（列表/详情）由阅读服务提供。
 * 删除漫画仅创建回收任务异步回收文件（软删除语义），更新走 {@code version} 乐观锁。
 */
@RestController
@RequestMapping("/api/manage/comics")
@RequiredArgsConstructor
public class ComicManagementController {

    private final ComicManagementService comicManagementService;

    /**
     * 创建空漫画（初始 DRAFT，尚未导入文件）。
     *
     * @param request 漫画初始信息（标题/作者等）
     * @return 新创建的漫画详情
     */
    @PostMapping
    public Result<ComicDetailVO> createComic(@Valid @RequestBody CreateComicRequest request) {
        return Result.ok(comicManagementService.createComic(request));
    }

    /**
     * 乐观锁更新漫画基本信息，{@code version} 冲突时返回 409。
     *
     * @param request 待更新字段（仅更新非空项）
     * @return 更新后的漫画详情
     */
    @PutMapping("/{id}")
    public Result<ComicDetailVO> updateComic(
            @PathVariable Long id,
            @Valid @RequestBody UpdateComicRequest request) {
        return Result.ok(comicManagementService.updateComic(id, request));
    }

    /**
     * 删除漫画：创建回收任务异步回收文件而非硬删。
     *
     * @param idempotencyKey 幂等键（可选），重复请求返回同一管理任务
     * @return 回收管理任务（可查询任务进度）
     */
    @DeleteMapping("/{id}")
    public Result<ManagementTaskResponse> deleteComic(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.ok(comicManagementService.deleteComic(id, idempotencyKey));
    }

    /**
     * 更新漫画元数据。
     *
     * @param dto 元数据待更新字段
     * @return 更新后的元数据
     */
    @PutMapping("/{id}/metadata")
    public Result<ComicMetadataDTO> updateMetadata(
            @PathVariable Long id,
            @Valid @RequestBody ComicMetadataUpdateRequest dto) {
        return Result.ok(comicManagementService.updateMetadata(id, dto));
    }

    /**
     * 全量覆盖漫画标签绑定关系。
     *
     * @param dto 新的标签 ID 集合
     * @return 空结果
     */
    @PutMapping("/{id}/tags")
    public Result<?> updateComicTags(
            @PathVariable Long id,
            @Valid @RequestBody ComicTagUpdateRequest dto) {
        comicManagementService.updateComicTags(id, dto);
        return Result.ok();
    }

    /**
     * 批量更新漫画（分类/标签），跨页一次性生效。
     *
     * @param dto 批量更新内容（categoryId 与 addTagIds 至少提供一项，否则 400）
     * @return 批量更新结果（成功/失败明细）
     */
    @PostMapping("/batch/update")
    public Result<BatchUpdateResultVO> batchUpdate(@Valid @RequestBody BatchComicUpdateRequest dto) {
        if (dto.getCategoryId() == null && (dto.getAddTagIds() == null || dto.getAddTagIds().isEmpty())) {
            return Result.fail(HttpStatusCodes.BAD_REQUEST, "至少需要提供 categoryId 或 addTagIds");
        }
        return Result.ok(comicManagementService.batchUpdate(dto));
    }
}
