package com.comicatlas.api.media.service.impl;

import com.comicatlas.api.media.dto.MediaReactionBatchRequest;
import com.comicatlas.api.media.dto.MediaReactionVO;
import com.comicatlas.api.media.service.MediaReactionManagementService;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.api.trash.service.TrashLifecycleService;
import com.comicatlas.contract.common.enums.MediaReaction;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 管理端媒体标记业务实现。 */
@Service
@RequiredArgsConstructor
public class MediaReactionManagementServiceImpl implements MediaReactionManagementService {
    private static final int MAX_BATCH_SIZE = 500;

    private final MediaMapper mediaMapper;
    private final TrashLifecycleService trashLifecycleService;

    @Override
    public List<MediaReactionVO> list(MediaReaction reaction, String mediaType, boolean includeTrashed) {
        return mediaMapper.selectReacted(reaction, mediaType, includeTrashed).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public int updateBatch(MediaReactionBatchRequest request) {
        validateBatch(request.getMediaIds());
        LocalDateTime reactionAt = request.getReaction() == MediaReaction.NONE
                ? null : LocalDateTime.now(ZoneOffset.UTC);
        return mediaMapper.updateReactionBatch(request.getMediaIds(), request.getReaction(), reactionAt);
    }

    @Override
    public List<OperationSubmitResultDTO> trashBatch(List<Long> mediaIds) {
        validateBatch(mediaIds);
        return mediaIds.stream().map(trashLifecycleService::trashMedia).toList();
    }

    private void validateBatch(List<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty() || mediaIds.size() > MAX_BATCH_SIZE
                || mediaIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "媒体批量操作数量必须在 1 到 500 之间，且 ID 有效");
        }
    }

    private MediaReactionVO toView(Media media) {
        MediaReactionVO view = new MediaReactionVO();
        view.setId(media.getId());
        view.setChapterId(media.getChapterId());
        view.setPageNumber(media.getPageNumber());
        view.setMediaType(media.getMediaType());
        view.setReaction(media.getReaction());
        view.setReactionAt(media.getReactionAt() == null
                ? null : media.getReactionAt().toInstant(ZoneOffset.UTC));
        view.setStatus(media.getStatus() == null ? null : media.getStatus().name());
        return view;
    }
}
