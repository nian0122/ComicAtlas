package com.comicatlas.api.importer.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.service.LqService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LqController {

    private final LqService lqService;

    @PostMapping("/comics/{comicId}/lq")
    public Result<?> generateComicLq(@PathVariable Long comicId) {
        lqService.generateForComic(comicId);
        return Result.ok();
    }

    @PostMapping("/chapters/{chapterId}/lq")
    public Result<?> generateChapterLq(@PathVariable Long chapterId) {
        lqService.generateForChapter(chapterId);
        return Result.ok();
    }
}
