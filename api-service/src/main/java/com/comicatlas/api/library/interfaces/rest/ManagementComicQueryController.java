package com.comicatlas.api.library.interfaces.rest;
import com.comicatlas.api.shared.application.model.PageResult;
import com.comicatlas.contract.common.dto.PageResponse;
import com.comicatlas.api.library.interfaces.rest.dto.ManagementComicListVO;
import com.comicatlas.api.library.application.port.in.ManagementComicQueryService;
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

/**
 * 管理域漫画查询接口，管理端不得跨域调用阅读服务查询接口。
 * <p>所有接口均为只读查询，不改变漫画、章节、页面或管理任务状态。</p>
 */
@RestController
@RequestMapping("/api/manage/comics")
@RequiredArgsConstructor
public class ManagementComicQueryController {
    private final ManagementComicQueryService queryService;

    /**
     * 分页查询管理端漫画列表。
     *
     * @param query 分页、排序及筛选条件
     * @return 管理端漫画分页结果
     */
    @GetMapping
    public Result<PageResponse<ManagementComicListVO>> list(ComicListQuery query) {
        PageResult<ManagementComicListVO> page = queryService.list(query);
        return Result.ok(PageResponse.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize()));
    }

    /**
     * 查询漫画详情。
     *
     * @param id 漫画 ID
     * @return 漫画详情
     */
    @GetMapping("/{id}")
    public Result<ComicDetailVO> detail(@PathVariable Long id) {
        return Result.ok(queryService.detail(id));
    }

    /**
     * 查询漫画元数据。
     *
     * @param id 漫画 ID
     * @return 漫画元数据
     */
    @GetMapping("/{id}/metadata")
    public Result<ComicMetadataDTO> metadata(@PathVariable Long id) {
        return Result.ok(queryService.metadata(id));
    }

    /**
     * 查询漫画关联的标签 ID。
     *
     * @param id 漫画 ID
     * @return 标签 ID 列表
     */
    @GetMapping("/{id}/tags")
    public Result<List<Long>> tags(@PathVariable Long id) {
        return Result.ok(queryService.tags(id));
    }
}
