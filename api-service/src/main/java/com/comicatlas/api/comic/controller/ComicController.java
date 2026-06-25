package com.comicatlas.api.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.comic.service.ComicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ComicController {

    private final ComicService comicService;

    @GetMapping("/comics")
    public Result<IPage<ComicListVO>> listComics(ComicListQuery query) {
        return Result.ok(comicService.listComics(query));
    }

    @GetMapping("/comics/{id}")
    public Result<ComicDetailVO> getComic(@PathVariable Long id) {
        return Result.ok(comicService.getComicDetail(id));
    }

    @DeleteMapping("/comics/{id}")
    public Result<?> deleteComic(@PathVariable Long id) {
        comicService.deleteComicAsync(id);
        return Result.ok();
    }

    @GetMapping("/comics/{id}/chapters/{chapterId}/pages")
    public Result<ChapterPageVO> getChapterPages(
            @PathVariable Long id,
            @PathVariable Long chapterId) {
        return Result.ok(comicService.getChapterPages(id, chapterId));
    }
}
