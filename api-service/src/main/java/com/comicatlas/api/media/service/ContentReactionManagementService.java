package com.comicatlas.api.media.service;

import com.comicatlas.api.media.dto.ContentReactionBatchRequest;
import com.comicatlas.api.media.dto.ContentReactionVO;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.contract.common.enums.MediaReaction;

import java.util.List;

/** 管理端漫画和章节标记查询与批量操作。 */
public interface ContentReactionManagementService {
    List<ContentReactionVO> list(String targetType, MediaReaction reaction, boolean includeTrashed);

    int updateBatch(String targetType, ContentReactionBatchRequest request);

    List<OperationSubmitResultDTO> trashBatch(String targetType, List<Long> ids);
}
