package com.comicatlas.api.reader.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.reader.dto.ReaderDTO;
import com.comicatlas.api.reader.service.ReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReaderController {

    private final ReaderService readerService;

    @GetMapping("/chapters/{id}")
    public Result<ReaderDTO> getChapter(@PathVariable Long id) {
        return Result.ok(readerService.getChapter(id));
    }
}
