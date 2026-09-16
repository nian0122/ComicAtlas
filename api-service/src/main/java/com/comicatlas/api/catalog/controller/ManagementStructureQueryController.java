package com.comicatlas.api.catalog.controller;
import com.comicatlas.api.catalog.service.ManagementStructureQueryService;
import com.comicatlas.contract.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理域目录树和章节结构查询接口。
 * <p>接口只读取管理服务中的目录与章节结构，不触发缓存失效或其它状态变更。</p>
 */
@RestController
@RequestMapping("/api/manage")
@RequiredArgsConstructor
public class ManagementStructureQueryController {
    private final ManagementStructureQueryService queryService;

    /**
     * 查询漫画目录树。
     *
     * @param comicId 漫画 ID
     * @return 目录树及其章节结构
     */
    @GetMapping("/comics/{comicId}/catalog")
    public Result<?> tree(@PathVariable Long comicId) { return Result.ok(queryService.tree(comicId)); }

    /**
     * 查询章节详情及其媒体结构。
     *
     * @param chapterId 章节 ID
     * @return 章节查询结果
     */
    @GetMapping("/chapters/{chapterId}")
    public Result<?> chapter(@PathVariable Long chapterId) { return Result.ok(queryService.chapter(chapterId)); }
}
