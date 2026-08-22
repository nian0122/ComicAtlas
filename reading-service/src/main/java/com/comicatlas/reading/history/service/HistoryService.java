package com.comicatlas.reading.history.service;

import com.comicatlas.reading.history.dto.HistoryUpdateRequest;
import com.comicatlas.reading.history.dto.HistoryPageVO;
import com.comicatlas.reading.history.dto.HistoryVO;

import java.util.List;

/**
 * 阅读历史接口（阅读域）。
 * <p>
 * 阅读历史查询与进度保存（upsert reading_history）归属阅读端，是阅读服务唯一的写操作。
 */
public interface HistoryService {

    List<HistoryVO> listHistory();

    HistoryPageVO pageHistory(long page, long size);

    HistoryVO getHistory(Long comicId);

    void upsertHistory(Long comicId, HistoryUpdateRequest request);
}
