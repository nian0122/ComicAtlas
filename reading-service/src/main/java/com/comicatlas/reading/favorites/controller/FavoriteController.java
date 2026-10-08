package com.comicatlas.reading.favorites.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.reading.favorites.dto.FavoriteDTO;
import com.comicatlas.reading.favorites.service.FavoriteService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 阅读端喜欢列表；标记写入复用既有阅读接口。 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@Validated
public class FavoriteController {
    private final FavoriteService favoriteService;

    /** 返回单页可阅读的喜欢内容。 */
    @GetMapping
    public Result<List<FavoriteDTO>> list(
            @RequestParam(defaultValue = "COMIC") @Pattern(regexp = "COMIC|CHAPTER|MEDIA") String targetType,
            @RequestParam(defaultValue = "false") boolean oldest,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(100) int size) {
        return Result.ok(favoriteService.list(targetType, oldest, page, size));
    }
}
