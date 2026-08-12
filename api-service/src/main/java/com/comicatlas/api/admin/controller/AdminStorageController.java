package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 存储管理查询接口（管理域）。
 * <p>
 * 基路径 {@code /api/admin/storage}，提供漫画/章节的存储占用与 HQ/LQ 状态查询，
 * 供管理端存储管理页使用。仅供本机管理端使用。
 */
@RestController
@RequestMapping("/api/manage/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageQueryService storageQueryService;

    /**
     * 分页查询漫画存储占用（支持按条件筛选，含分页信息）。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @param query 筛选条件（漫画名/HQ-LQ 状态等）
     * @return 分页结果（records/total/pages/current）
     */
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

    /**
     * 查询漫画各章节的存储占用与 HQ/LQ 状态。
     *
     * @param comicId 漫画 ID
     * @return 章节存储列表
     */
    @GetMapping("/comics/{comicId}/chapters")
    public Result<List<ChapterStorageDTO>> listChapters(@PathVariable Long comicId) {
        return Result.ok(storageQueryService.listChapters(comicId));
    }

    /**
     * 查询单本漫画的存储详情（不存在时返回 404）。
     *
     * @param comicId 漫画 ID
     * @return 漫画存储详情
     */
    @GetMapping("/comics/{comicId}")
    public Result<ComicStorageDTO> getComic(@PathVariable Long comicId) {
        ComicStorageDTO dto = storageQueryService.getComic(comicId);
        if (dto == null) {
            return Result.fail(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return Result.ok(dto);
    }
}
