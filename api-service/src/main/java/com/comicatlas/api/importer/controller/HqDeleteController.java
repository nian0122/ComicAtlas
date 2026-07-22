package com.comicatlas.api.importer.controller;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.importer.exception.HqDeletePreconditionException;
import com.comicatlas.api.importer.service.HqDeleteResult;
import com.comicatlas.api.importer.service.HqDeleteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HqDeleteController {
    private final HqDeleteService hqDeleteService;

    @PostMapping("/api/comics/{comicId}/delete-hq")
    public Result<?> deleteComicHq(@PathVariable Long comicId) {
        log.info("请求删除漫画 HQ: comicId={}", comicId);
        HqDeleteResult result = hqDeleteService.deleteForComic(comicId);
        return result == HqDeleteResult.ALREADY_DELETED
            ? Result.ok()
            : Result.ok(202, "已提交 HQ 删除任务");
    }

    @PostMapping("/api/chapters/{chapterId}/delete-hq")
    public Result<?> deleteChapterHq(@PathVariable Long chapterId) {
        log.info("请求删除章节 HQ: chapterId={}", chapterId);
        HqDeleteResult result = hqDeleteService.deleteForChapter(chapterId);
        return result == HqDeleteResult.ALREADY_DELETED
            ? Result.ok()
            : Result.ok(202, "已提交 HQ 删除任务");
    }

    @ExceptionHandler(HqDeletePreconditionException.class)
    public Result<?> handlePreconditionException(HqDeletePreconditionException e) {
        return Result.fail(409, e.getMessage());
    }
}
