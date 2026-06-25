package com.comicatlas.api.reader.service;

import com.comicatlas.api.reader.dto.*;
import java.util.List;

public interface HistoryService {
    List<HistoryVO> listHistory();
    HistoryVO getHistory(Long comicId);
    void upsertHistory(Long comicId, HistoryUpdateRequest request);
}
