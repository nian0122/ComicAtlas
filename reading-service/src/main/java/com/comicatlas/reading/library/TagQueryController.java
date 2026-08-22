package com.comicatlas.reading.library;

import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.reading.library.TagQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签查询接口（阅读域）。
 * <p>
 * 基路径 {@code /api/tags}，提供标签列表供阅读端筛选。
 * 标签创建/删除由管理服务提供。
 */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagQueryController {

    private final TagQueryService tagQueryService;

    /**
     * 查询全部标签。
     *
     * @return 标签列表
     */
    @GetMapping
    public Result<List<TagDTO>> listTags() {
        return Result.ok(tagQueryService.listTags());
    }
}
