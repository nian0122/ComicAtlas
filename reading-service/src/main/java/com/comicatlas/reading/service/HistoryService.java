package com.comicatlas.reading.service;

import com.comicatlas.reading.dto.HistoryUpdateRequest;
import com.comicatlas.reading.dto.HistoryVO;

import java.util.List;

/**
 * 阅读历史接口（阅读域）。
 * <p>
 * 阅读历史查询与进度保存（upsert reading_history）归属阅读端，是阅读服务唯一的写操作。
 */
public interface HistoryService {

    List<HistoryVO> listHistory();

    HistoryVO getHistory(Long comicId);

    void upsertHistory(Long comicId, HistoryUpdateRequest request);
}
