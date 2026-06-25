package com.comicatlas.api.operation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.operation.dto.OperationLogVO;

public interface OperationLogService {
    IPage<OperationLogVO> listLogs(String module, String action, String businessId, String keyword, Integer page, Integer size);
}
