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
import com.comicatlas.api.comic.dto.ChapterPageVO;
import com.comicatlas.api.comic.dto.ComicDetailVO;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.ComicMetadataUpdateDTO;
import com.comicatlas.api.comic.dto.ComicTagUpdateDTO;
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ComicController {

    private final ComicService comicService;

    @GetMapping("/comics")
    public Result<IPage<ComicListVO>> listComics(ComicListQuery query) {
        return Result.ok(comicService.listComics(query));
    }

    /** 创建空漫画（DRAFT） */
    @PostMapping("/comics")
    public Result<ComicDetailVO> createComic(@Valid @RequestBody CreateComicRequest request) {
        return Result.ok(comicService.createComic(request));
    }

    /** 乐观锁更新漫画（version 冲突 → 409） */
    @PutMapping("/comics/{id}")
    public Result<ComicDetailVO> updateComic(
            @PathVariable Long id,
            @Valid @RequestBody UpdateComicRequest request) {
        return Result.ok(comicService.updateComic(id, request));
    }

    @GetMapping("/comics/{id}")
    public Result<ComicDetailVO> getComic(@PathVariable Long id) {
        return Result.ok(comicService.getComicDetail(id));
    }

    /** 删除漫画：创建回收任务而非硬删 */
    @DeleteMapping("/comics/{id}")
    public Result<ManagementTaskResponse> deleteComic(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.ok(comicService.deleteComic(id, idempotencyKey));
    }

    @GetMapping("/comics/{id}/chapters/{chapterId}/pages")
    public Result<ChapterPageVO> getChapterPages(
            @PathVariable Long id,
            @PathVariable Long chapterId) {
        return Result.ok(comicService.getChapterPages(id, chapterId));
    }

    @GetMapping("/comics/{id}/metadata")
    public Result<ComicMetadataDTO> getMetadata(@PathVariable Long id) {
        return Result.ok(comicService.getMetadata(id));
    }

    @PutMapping("/comics/{id}/metadata")
    public Result<ComicMetadataDTO> updateMetadata(
            @PathVariable Long id,
            @Valid @RequestBody ComicMetadataUpdateDTO dto) {
        return Result.ok(comicService.updateMetadata(id, dto));
    }

    @GetMapping("/comics/{id}/tags")
    public Result<List<Long>> getComicTags(@PathVariable Long id) {
        return Result.ok(comicService.getComicTags(id));
    }

    @PutMapping("/comics/{id}/tags")
    public Result<?> updateComicTags(
            @PathVariable Long id,
            @Valid @RequestBody ComicTagUpdateDTO dto) {
        comicService.updateComicTags(id, dto);
        return Result.ok();
    }

    @PostMapping("/comics/batch/update")
    public Result<BatchUpdateResultVO> batchUpdate(@Valid @RequestBody BatchComicUpdateDTO dto) {
        if (dto.getCategoryId() == null && (dto.getAddTagIds() == null || dto.getAddTagIds().isEmpty())) {
            return Result.fail(HttpStatusCodes.BAD_REQUEST, "至少需要提供 categoryId 或 addTagIds");
        }
        return Result.ok(comicService.batchUpdate(dto));
    }

    @GetMapping("/comics/autocomplete")
    public Result<List<String>> autocompleteTitles(@RequestParam String keyword) {
        return Result.ok(comicService.autocompleteTitles(keyword));
    }

}
