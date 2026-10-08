package com.comicatlas.api.media.service;

import com.comicatlas.api.media.dto.MediaReactionBatchRequest;
import com.comicatlas.api.media.dto.MediaReactionVO;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.contract.common.enums.MediaReaction;

import java.util.List;

/** 管理端媒体标记查询和批量操作。 */
public interface MediaReactionManagementService {
    List<MediaReactionVO> list(MediaReaction reaction, String mediaType, boolean includeTrashed);

    int updateBatch(MediaReactionBatchRequest request);

    List<OperationSubmitResultDTO> trashBatch(List<Long> mediaIds);
}
