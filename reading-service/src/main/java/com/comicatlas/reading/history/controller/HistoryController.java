package com.comicatlas.reading.history.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.reading.history.dto.HistoryUpdateRequest;
import com.comicatlas.reading.history.dto.HistoryPageVO;
import com.comicatlas.reading.history.dto.HistoryVO;
import com.comicatlas.reading.history.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阅读历史接口（阅读域）。
 * <p>
 * 基路径 {@code /api/history}，提供阅读历史列表、单本漫画历史查询与进度更新。
 * 进度通过 upsert 写入，重复提交幂等。
 */
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    /**
     * 查询全部阅读历史（按最近阅读排序）。
     *
     * @return 阅读历史列表
     */
    @GetMapping
    public Result<?> listHistory() {
        return Result.ok(historyService.listHistory());
    }

    /**
     * 分页查询阅读历史，供历史页滚动加载使用。
     */
    @GetMapping("/page")
    public Result<HistoryPageVO> pageHistory(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(historyService.pageHistory(page, size));
    }

    /**
     * 查询单本漫画的阅读历史。
     *
     * @param comicId 漫画 ID
     * @return 该漫画的历史记录（最近阅读章节/进度等）
     */
    @GetMapping("/{comicId}")
    public Result<HistoryVO> getHistory(@PathVariable Long comicId) {
        return Result.ok(historyService.getHistory(comicId));
    }

    /**
     * 更新（新增或覆盖）单本漫画的阅读进度。
     *
     * @param comicId 漫画 ID
     * @param request 进度信息（章节 ID/页码等）
     * @return 空结果
     */
    @PutMapping("/{comicId}")
    public Result<?> updateHistory(@PathVariable Long comicId,
                                    @RequestBody HistoryUpdateRequest request) {
        historyService.upsertHistory(comicId, request);
        return Result.ok();
    }
}
