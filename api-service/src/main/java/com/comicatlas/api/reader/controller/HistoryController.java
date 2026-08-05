package com.comicatlas.api.reader.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.reader.service.HistoryService;
import lombok.RequiredArgsConstructor;
import com.comicatlas.api.reader.dto.HistoryUpdateRequest;
import com.comicatlas.api.reader.dto.HistoryVO;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
