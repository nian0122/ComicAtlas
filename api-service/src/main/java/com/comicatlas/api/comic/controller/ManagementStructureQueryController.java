package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.service.ManagementStructureQueryService;
import com.comicatlas.contract.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理域结构查询接口。 */
@RestController
@RequestMapping("/api/manage")
@RequiredArgsConstructor
public class ManagementStructureQueryController {
    private final ManagementStructureQueryService queryService;

    @GetMapping("/comics/{comicId}/catalog")
    public Result<?> tree(@PathVariable Long comicId) { return Result.ok(queryService.tree(comicId)); }

    @GetMapping("/chapters/{chapterId}")
    public Result<?> chapter(@PathVariable Long chapterId) { return Result.ok(queryService.chapter(chapterId)); }
}
