package com.comicatlas.api.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Media 元信息同步服务（存储操作域）。
 * <p>
 * 转码等操作导致 media 元信息变更后，负责：刷新章节/漫画统计、失效目录缓存、
 * 触发 metadata.json 重导出（metadata.refresh.requested 事件走 Outbox，与业务同事务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMetadataSyncService {

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final OutboxService outboxService;

    /**
     * 转码完成后同步：更新漫画/章节统计并触发 metadata.json 重导出。
     */
    public void notifyTranscoded(Long mediaId, Long taskId) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null || media.getChapterId() == null) {
            return;
        }
        Chapter chapter = chapterMapper.selectById(media.getChapterId());
        if (chapter == null) {
            return;
        }
        refreshChapterAndComicStats(chapter.getId());
        publishMetadataRefresh(chapter.getComicId(), taskId, "mediaId=" + mediaId);
    }

    /**
     * 整本转码任务全部完成后同步：一次性聚合整本统计并触发 metadata.json 重导出。
     * 由结果事件处理器在任务无剩余未完成项时调用，避免每个视频各自重导出一次。
     */
    public void notifyTaskTranscoded(Long comicId, Long taskId) {
        if (comicId == null) {
            return;
        }
        refreshComicStats(comicId);
        publishMetadataRefresh(comicId, taskId, "taskId=" + taskId);
    }

    /**
     * 失效目录缓存并发送 metadata.refresh.requested（Outbox 入箱，由 relay 异步发布）。
     * <p>
     * 与业务同事务写入 outbox_message，保证 DB 变更与消息发布的最终一致性；
     * 禁止吞异常——Outbox 写入失败必须向上传播，由调用方决定重试/死信策略。
     */
    private void publishMetadataRefresh(Long comicId, Long taskId, String source) {
        catalogCacheInvalidator.evict(comicId);
        outboxService.enqueue(new MetadataRefreshEvent(null, null, comicId),
                MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED);
        log.info("转码后元数据同步已触发（Outbox）: comicId={}, taskId={}, source={}", comicId, taskId, source);
    }

    /** 整本统计聚合（一次性）：各章节页数、漫画总页数与整本 HQ 大小。 */
    private void refreshComicStats(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (chapters.isEmpty()) {
            return;
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        long totalPages = 0;
        for (Chapter chapter : chapters) {
            long pageCount = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                    .eq(Media::getChapterId, chapter.getId())
                    .notIn(Media::getStatus, "DELETED", "TRASHED"));
            totalPages += pageCount;
            chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                    .eq(Chapter::getId, chapter.getId())
                    .set(Chapter::getPageCount, (int) pageCount));
        }
        long hqSize = mediaMapper.selectList(
                        new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds)).stream()
                .filter(p -> p.getHqStatus() != HqStatus.DELETED)
                .mapToLong(p -> p.getFileSize() != null ? p.getFileSize() : 0L)
                .sum();
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comicId)
                .set(Comic::getTotalPages, (int) totalPages)
                .set(Comic::getHqSize, hqSize));
    }

    /** 与 ManagementCommandResultHandler.refreshChapterAndComicStats 同款聚合：章节页数 + 漫画总页数 + 整本 HQ 大小。 */
    private void refreshChapterAndComicStats(Long chapterId) {
        if (chapterId == null) {
            return;
        }
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        long pageCount = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .notIn(Media::getStatus, "DELETED", "TRASHED"));
        chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getId, chapterId)
                .set(Chapter::getPageCount, (int) pageCount));
        recomputeComicHqSize(chapterId);
        Comic comic = comicMapper.selectById(chapter.getComicId());
        if (comic != null) {
            List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comic.getId()));
            List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
            if (!chapterIds.isEmpty()) {
                long totalPages = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .notIn(Media::getStatus, "DELETED", "TRASHED"));
                comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                        .eq(Comic::getId, comic.getId())
                        .set(Comic::getTotalPages, (int) totalPages));
            }
        }
    }

    /**
     * 统计量从实际 media 行重算整本 comic.hqSize（HQ 状态非 DELETED 的文件大小求和），
     * 与 ManagementCommandResultHandler.recomputeComicHqSize 保持一致。
     */
    private void recomputeComicHqSize(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        Long comicId = chapter.getComicId();
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (chapters.isEmpty()) {
            return;
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        long hqSize = mediaItems.stream()
                .filter(p -> p.getHqStatus() != HqStatus.DELETED)
                .mapToLong(p -> p.getFileSize() != null ? p.getFileSize() : 0L)
                .sum();
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null) {
            comic.setHqSize(hqSize);
            comicMapper.updateById(comic);
        }
        log.debug("重算 comic.hqSize: comicId={}, hqSize={}", comicId, hqSize);
    }
}
