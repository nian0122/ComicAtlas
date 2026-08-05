package com.comicatlas.api.importer.controller;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.service.HqDeleteService;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HqDeleteController {
    private final HqDeleteService hqDeleteService;

    @PostMapping("/api/comics/{comicId}/delete-hq")
    public Result<OperationSubmitResult> deleteComicHq(@PathVariable Long comicId) {
        log.info("请求删除漫画 HQ: comicId={}", comicId);
        return Result.ok(hqDeleteService.deleteForComic(comicId));
    }

    @PostMapping("/api/chapters/{chapterId}/delete-hq")
    public Result<OperationSubmitResult> deleteChapterHq(@PathVariable Long chapterId) {
        log.info("请求删除章节 HQ: chapterId={}", chapterId);
        return Result.ok(hqDeleteService.deleteForChapter(chapterId));
    }
}
