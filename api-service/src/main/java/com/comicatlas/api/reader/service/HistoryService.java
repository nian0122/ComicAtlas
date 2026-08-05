package com.comicatlas.api.reader.service;

import java.util.List;
import com.comicatlas.api.reader.dto.HistoryUpdateRequest;
import com.comicatlas.api.reader.dto.HistoryVO;

public interface HistoryService {
    List<HistoryVO> listHistory();
    HistoryVO getHistory(Long comicId);
    void upsertHistory(Long comicId, HistoryUpdateRequest request);
}
