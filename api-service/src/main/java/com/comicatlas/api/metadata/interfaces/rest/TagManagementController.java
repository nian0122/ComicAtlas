package com.comicatlas.api.metadata.interfaces.rest;
import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.api.metadata.interfaces.rest.dto.CreateTagRequest;
import com.comicatlas.api.metadata.application.port.in.TagManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 标签管理接口（管理域）。
 * <p>
 * 基路径 {@code /api/manage/tags}，提供标签创建与删除。
 * 标签名称唯一（重复创建返回 409），已被漫画引用的标签禁止删除。
 * 标签查询（列表）由阅读服务提供。
 */
@RestController
@RequestMapping("/api/manage/tags")
@RequiredArgsConstructor
public class TagManagementController {

    private final TagManagementService tagManagementService;

    /**
     * 查询管理端可用的标签列表；该查询不改变标签或漫画状态。
     *
     * @return 标签列表
     */
    @GetMapping
    public Result<java.util.List<TagDTO>> listTags() {
        return Result.ok(tagManagementService.listTags());
    }

    /**
     * 创建标签，名称不能为空（@Valid 边界校验）。
     *
     * @param request 标签信息
     * @return 创建的标签
     */
    @PostMapping
    public Result<TagDTO> createTag(@Valid @RequestBody CreateTagRequest request) {
        return Result.ok(tagManagementService.createTag(request.getName().trim()));
    }

    /**
     * 删除标签；已被漫画引用的标签由服务层拒绝删除，成功后不再出现在可用标签中。
     *
     * @param id 标签 ID
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<?> deleteTag(@PathVariable Long id) {
        tagManagementService.deleteTag(id);
        return Result.ok();
    }
}
