package com.comicatlas.reading.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.dto.ComicListVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.reading.service.ComicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 漫画查询接口（阅读域）。
 * <p>
 * 基路径 {@code /api}，提供漫画分页列表、详情、元数据、标签与标题自动补全等只读查询，
 * 供阅读端与详情页渲染。漫画写操作由管理服务提供。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReadingComicController {

    private final ComicQueryService comicQueryService;

    /**
     * 分页查询漫画列表。
     *
     * @param query 分页与筛选条件（标题/状态/分类/排序等）
     * @return 漫画分页数据（列表 VO）
     */
    @GetMapping("/comics")
    public Result<IPage<ComicListVO>> listComics(ComicListQuery query) {
        return Result.ok(comicQueryService.listComics(query));
    }

    /**
     * 查询漫画详情。
     *
     * @return 漫画详情（含章节/元数据等）
     */
    @GetMapping("/comics/{id}")
    public Result<ComicDetailVO> getComic(@PathVariable Long id) {
        return Result.ok(comicQueryService.getComicDetail(id));
    }

    /**
     * 查询漫画元数据。
     *
     * @return 元数据（标题/作者/描述等可编辑字段）
     */
    @GetMapping("/comics/{id}/metadata")
    public Result<ComicMetadataDTO> getMetadata(@PathVariable Long id) {
        return Result.ok(comicQueryService.getMetadata(id));
    }

    /**
     * 查询漫画绑定的标签 ID 列表。
     *
     * @return 标签 ID 列表
     */
    @GetMapping("/comics/{id}/tags")
    public Result<List<Long>> getComicTags(@PathVariable Long id) {
        return Result.ok(comicQueryService.getComicTags(id));
    }

    /**
     * 按关键字自动补全漫画标题。
     *
     * @param keyword 标题关键字
     * @return 匹配的标题列表
     */
    @GetMapping("/comics/autocomplete")
    public Result<List<String>> autocompleteTitles(@RequestParam String keyword) {
        return Result.ok(comicQueryService.autocompleteTitles(keyword));
    }
}
