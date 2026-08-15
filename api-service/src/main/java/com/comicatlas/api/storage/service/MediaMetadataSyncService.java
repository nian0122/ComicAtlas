package com.comicatlas.api.storage.service;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Media 元信息同步服务（存储操作域）。
 * <p>
 * 转码等操作导致 media 元信息变更后，负责：委托 {@link ComicStatsService} 刷新章节/漫画统计、
 * 失效目录缓存、触发 metadata.json 重导出（metadata.refresh.requested 事件走 Outbox，与业务同事务）。
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
    private final ComicStatsService comicStatsService;

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
        comicStatsService.refreshByChapter(chapter.getId());
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
        comicStatsService.refreshByComic(comicId);
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
}
