package com.comicatlas.api.library.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.library.dto.ManagementComicListVO;
import com.comicatlas.api.library.service.ManagementComicQueryService;
import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.comicatlas.contract.comic.dto.ComicListQuery;

/** 管理域漫画查询接口，管理端不得跨域调用阅读服务查询接口。 */
@RestController
@RequestMapping("/api/manage/comics")
@RequiredArgsConstructor
public class ManagementComicQueryController {
    private final ManagementComicQueryService queryService;

    @GetMapping
    public Result<IPage<ManagementComicListVO>> list(ComicListQuery query) {
        return Result.ok(queryService.list(query));
    }

    @GetMapping("/{id}")
    public Result<ComicDetailVO> detail(@PathVariable Long id) {
        return Result.ok(queryService.detail(id));
    }

    @GetMapping("/{id}/metadata")
    public Result<ComicMetadataDTO> metadata(@PathVariable Long id) {
        return Result.ok(queryService.metadata(id));
    }

    @GetMapping("/{id}/tags")
    public Result<List<Long>> tags(@PathVariable Long id) {
        return Result.ok(queryService.tags(id));
    }
}
