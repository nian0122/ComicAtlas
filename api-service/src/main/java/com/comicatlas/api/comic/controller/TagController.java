package com.comicatlas.api.comic.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.TagDTO;
import com.comicatlas.api.comic.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public Result<List<TagDTO>> listTags() {
        return Result.ok(tagService.listTags());
    }

    @PostMapping
    public Result<TagDTO> createTag(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return Result.fail(400, "标签名称不能为空");
        }
        return Result.ok(tagService.createTag(name.trim()));
    }

    @DeleteMapping("/{id}")
    public Result<?> deleteTag(@PathVariable Long id) {
        tagService.deleteTag(id);
        return Result.ok();
    }
}
