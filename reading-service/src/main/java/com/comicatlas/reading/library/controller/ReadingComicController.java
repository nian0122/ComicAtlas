package com.comicatlas.reading.library.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.common.dto.PageResponse;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.dto.ComicListPage;
import com.comicatlas.reading.library.dto.ComicListVO;
import com.comicatlas.reading.library.service.ComicListQueryService;
import com.comicatlas.reading.library.service.ComicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 漫画查询接口（阅读域）。
 * <p>
 * 基路径 {@code /api}，提供漫画分页列表与详情查询，
 * 供阅读端与详情页渲染。漫画写操作由管理服务提供。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReadingComicController {

    private final ComicQueryService comicQueryService;
    private final ComicListQueryService comicListQueryService;

    /**
     * 分页查询漫画列表。
     *
     * @param query 分页与筛选条件（标题/状态/分类/排序等）
     * @return 漫画分页数据（列表 VO）
     */
    @GetMapping("/comics")
    public Result<PageResponse<ComicListVO>> listComics(ComicListQuery query) {
        ComicListPage comicPage = comicListQueryService.listComics(query);
        return Result.ok(PageResponse.of(comicPage.getRecords(), comicPage.getTotal(),
                comicPage.getCurrent(), comicPage.getSize()));
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

}
