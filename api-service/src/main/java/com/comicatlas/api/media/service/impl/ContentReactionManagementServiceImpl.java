package com.comicatlas.api.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.media.dto.ContentReactionBatchRequest;
import com.comicatlas.api.media.dto.ContentReactionVO;
import com.comicatlas.api.media.service.ContentReactionManagementService;
import com.comicatlas.api.trash.service.TrashLifecycleService;
import com.comicatlas.api.task.dto.OperationSubmitResultDTO;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaReaction;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 管理端漫画和章节标记业务实现。 */
@Service
@RequiredArgsConstructor
public class ContentReactionManagementServiceImpl implements ContentReactionManagementService {
    private static final int MAX_BATCH_SIZE = 500;
    private static final String COMIC = "COMIC";
    private static final String CHAPTER = "CHAPTER";

    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final TrashLifecycleService trashLifecycleService;

    @Override
    public List<ContentReactionVO> list(String targetType, MediaReaction reaction, boolean includeTrashed) {
        validateTargetType(targetType);
        if (COMIC.equals(targetType)) {
            LambdaQueryWrapper<Comic> query = new LambdaQueryWrapper<Comic>()
                    .in(Comic::getReaction, MediaReaction.LIKE, MediaReaction.DISLIKE)
                    .orderByDesc(Comic::getReactionAt).orderByAsc(Comic::getId);
            if (reaction != null) {
                query.eq(Comic::getReaction, reaction);
            }
            if (!includeTrashed) {
                query.eq(Comic::getStatus, ComicStatus.READY);
            }
            return comicMapper.selectList(query).stream().map(this::toComicView).toList();
        }
        LambdaQueryWrapper<Chapter> query = new LambdaQueryWrapper<Chapter>()
                .in(Chapter::getReaction, MediaReaction.LIKE, MediaReaction.DISLIKE)
                .orderByDesc(Chapter::getReactionAt).orderByAsc(Chapter::getId);
        if (reaction != null) {
            query.eq(Chapter::getReaction, reaction);
        }
        if (!includeTrashed) {
            query.eq(Chapter::getStatus, ChapterLifecycleStatus.READY);
        }
        return chapterMapper.selectList(query).stream().map(this::toChapterView).toList();
    }

    @Override
    @Transactional
    public int updateBatch(String targetType, ContentReactionBatchRequest request) {
        validateTargetType(targetType);
        validateIds(request.getIds());
        LocalDateTime reactionAt = request.getReaction() == MediaReaction.NONE
                ? null : LocalDateTime.now(ZoneOffset.UTC);
        if (COMIC.equals(targetType)) {
            return comicMapper.updateReactionBatch(request.getIds(), request.getReaction(), reactionAt);
        }
        return chapterMapper.updateReactionBatch(request.getIds(), request.getReaction(), reactionAt);
    }

    @Override
    public List<OperationSubmitResultDTO> trashBatch(String targetType, List<Long> ids) {
        validateTargetType(targetType);
        validateIds(ids);
        if (COMIC.equals(targetType)) {
            return ids.stream().map(id -> trashLifecycleService.trashComic(id, null)).toList();
        }
        return ids.stream().map(id -> {
            Chapter chapter = chapterMapper.selectById(id);
            if (chapter == null) {
                throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + id);
            }
            return trashLifecycleService.trashChapter(chapter.getComicId(), id);
        }).toList();
    }

    private void validateTargetType(String targetType) {
        if (!COMIC.equals(targetType) && !CHAPTER.equals(targetType)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标记目标类型必须是 COMIC 或 CHAPTER");
        }
    }

    private void validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BATCH_SIZE
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "批量操作数量必须在 1 到 500 之间，且 ID 有效");
        }
    }

    private ContentReactionVO toComicView(Comic comic) {
        ContentReactionVO view = new ContentReactionVO();
        view.setId(comic.getId());
        view.setTargetType(COMIC);
        view.setTitle(comic.getTitle());
        view.setReaction(comic.getReaction());
        view.setReactionAt(toInstant(comic.getReactionAt()));
        view.setStatus(comic.getStatus() == null ? null : comic.getStatus().name());
        return view;
    }

    private ContentReactionVO toChapterView(Chapter chapter) {
        ContentReactionVO view = new ContentReactionVO();
        view.setId(chapter.getId());
        view.setTargetType(CHAPTER);
        view.setComicId(chapter.getComicId());
        view.setTitle(chapter.getTitle());
        view.setReaction(chapter.getReaction());
        view.setReactionAt(toInstant(chapter.getReactionAt()));
        view.setStatus(chapter.getStatus() == null ? null : chapter.getStatus().name());
        return view;
    }

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
