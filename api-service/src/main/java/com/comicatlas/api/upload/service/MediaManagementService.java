package com.comicatlas.api.upload.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.api.upload.dto.MediaReorderItem;
import com.comicatlas.api.upload.dto.MediaReorderRequest;
import com.comicatlas.api.upload.dto.MediaReorderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 媒体管理服务 — 章节内重排（临时偏移避免唯一键冲突）与回收站删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaManagementService {

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final TrashLifecycleService trashLifecycleService;

    // ======================== 章节内重排 ========================

    /**
     * 章节内媒体重排：仅改 pageNumber，两阶段更新避免 uk_chapter_page 瞬时冲突。
     * 阶段一 page_number = -id（id 唯一正数，负值绝不冲突），阶段二写回 1..N。
     */
    @Transactional
    public MediaReorderResponse reorder(Long chapterId, MediaReorderRequest request) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在: " + chapterId);
        }
        List<Long> mediaIds = request.getMediaIds();
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "媒体列表存在重复项");
        }
        List<Media> existing = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId));
        Set<Long> existingIds = new HashSet<>();
        for (Media media : existing) {
            existingIds.add(media.getId());
        }
        for (Long id : mediaIds) {
            if (!existingIds.contains(id)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "媒体 " + id + " 不属于章节 " + chapterId);
            }
        }
        if (existingIds.size() != mediaIds.size()) {
            throw new ConflictException("章节媒体数量与重排列表不一致，请刷新后重试");
        }

        // 阶段一：临时唯一负偏移
        mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .setSql("page_number = -id"));

        // 阶段二：按新顺序写回 1..N（乐观锁校验）
        List<MediaReorderItem> items = new ArrayList<>(mediaIds.size());
        for (int i = 0; i < mediaIds.size(); i++) {
            Media media = mediaMapper.selectById(mediaIds.get(i));
            media.setPageNumber(i + 1);
            int rows = mediaMapper.updateById(media);
            if (rows == 0) {
                throw new ConflictException("媒体已被并发修改，请刷新后重试");
            }
            MediaReorderItem item = new MediaReorderItem();
            item.setMediaId(media.getId());
            item.setPageNumber(i + 1);
            items.add(item);
        }

        MediaReorderResponse resp = new MediaReorderResponse();
        resp.setItems(items);
        log.info("章节媒体重排: chapterId={}, count={}", chapterId, mediaIds.size());
        return resp;
    }

    // ======================== 删除（回收站） ========================

    /**
     * 媒体删除：进入回收管线（MEDIA_TRASH），不硬删。
     * READY → TRASHING（写入清单）→ Worker 移入 TRASH → 结果回 TRASHED。
     */
    @Transactional
    public OperationSubmitResultDTO trash(Long mediaId) {
        return trashLifecycleService.trashMedia(mediaId);
    }
}
