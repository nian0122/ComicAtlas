package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageQueryService storageQueryService;

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
}
