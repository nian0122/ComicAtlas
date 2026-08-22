package com.comicatlas.api.media.service;

import com.comicatlas.api.metadata.service.MetadataUpdateCoordinator;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Media 元信息同步服务（存储操作域）。
 * <p>
 * 转码等操作导致 media 元信息变更后，负责：委托 {@link ComicStatsService} 刷新章节/漫画统计，
 * 并委托 {@link MetadataUpdateCoordinator} 按 comicId 合并触发 metadata.json 重导出
 * （Outbox 入箱与 Worker 原子写由 Coordinator 统一编排，本服务不直接接触 MQ 与文件系统）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMetadataSyncService {

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicStatsService comicStatsService;
    private final MetadataUpdateCoordinator metadataUpdateCoordinator;

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
        metadataUpdateCoordinator.requestSync(chapter.getComicId(), taskId, "转码完成: mediaId=" + mediaId);
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
        metadataUpdateCoordinator.requestSync(comicId, taskId, "转码任务完成: taskId=" + taskId);
    }
}
