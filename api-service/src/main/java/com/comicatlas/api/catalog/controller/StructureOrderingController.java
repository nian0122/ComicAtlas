package com.comicatlas.api.catalog.controller;

import com.comicatlas.api.catalog.dto.StructureOrderRequest;
import com.comicatlas.api.catalog.service.StructureOrderingService;
import com.comicatlas.contract.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 漫画目录树统一排序入口。 */
@RestController
@RequestMapping("/api/manage/comics/{comicId}/structure")
@RequiredArgsConstructor
public class StructureOrderingController {

    private final StructureOrderingService structureOrderingService;

    /** 更新同一父目录下目录与章节的完整展示顺序。 */
    @PutMapping("/reorder")
    public Result<Void> reorder(
            @PathVariable Long comicId,
            @Valid @RequestBody StructureOrderRequest request) {
        structureOrderingService.reorder(comicId, request);
        return Result.ok();
    }
}
