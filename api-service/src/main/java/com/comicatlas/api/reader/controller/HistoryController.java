package com.comicatlas.api.reader.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.reader.dto.*;
import com.comicatlas.api.reader.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping
    public Result<?> listHistory() {
        return Result.ok(historyService.listHistory());
    }

    @GetMapping("/{comicId}")
    public Result<HistoryVO> getHistory(@PathVariable Long comicId) {
        return Result.ok(historyService.getHistory(comicId));
    }

    @PutMapping("/{comicId}")
    public Result<?> updateHistory(@PathVariable Long comicId,
                                    @RequestBody HistoryUpdateRequest request) {
        historyService.upsertHistory(comicId, request);
        return Result.ok();
    }
}
