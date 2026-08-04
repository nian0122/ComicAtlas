package com.comicatlas.api.importer.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.service.LqService;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LqController {

    private final LqService lqService;

    @PostMapping("/comics/{comicId}/lq")
    public Result<OperationSubmitResult> generateComicLq(
            @PathVariable Long comicId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqService.generateForComic(comicId, regenerate));
    }

    @PostMapping("/chapters/{chapterId}/lq")
    public Result<OperationSubmitResult> generateChapterLq(
            @PathVariable Long chapterId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return Result.ok(lqService.generateForChapter(chapterId, regenerate));
    }
}
