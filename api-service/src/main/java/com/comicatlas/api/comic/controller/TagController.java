package com.comicatlas.api.comic.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.comic.dto.TagDTO;
import com.comicatlas.api.comic.service.TagService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 漫画标签管理接口。
 * <p>
 * 基路径 {@code /api/tags}，提供标签列表、创建与删除。
 * 标签名称唯一（重复创建返回 409），已被漫画引用的标签禁止删除。
 */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /**
     * 查询全部标签。
     *
     * @return 标签列表
     */
    @GetMapping
    public Result<List<TagDTO>> listTags() {
        return Result.ok(tagService.listTags());
    }

    /**
     * 创建标签，名称不能为空。
     *
     * @param body 请求体，仅读取 {@code name} 字段
     * @return 创建的标签
     */
    @PostMapping
    public Result<TagDTO> createTag(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return Result.fail(HttpStatusCodes.BAD_REQUEST, "标签名称不能为空");
        }
        return Result.ok(tagService.createTag(name.trim()));
    }

    /**
     * 删除标签；已被漫画引用时返回 409。
     *
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<?> deleteTag(@PathVariable Long id) {
        tagService.deleteTag(id);
        return Result.ok();
    }
}
