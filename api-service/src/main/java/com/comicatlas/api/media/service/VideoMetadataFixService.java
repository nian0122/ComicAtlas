package com.comicatlas.api.media.service;

import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;
import com.comicatlas.common.event.payload.VideoMetadataFixResult;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 视频元数据修复结果应用服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetadataFixService {
    // 视频修复契约由应用服务公开，具体实现保持在媒体业务包内。
    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;

    @Transactional
    public void apply(VideoMetadataFixCompletedEvent event) {
        int fixed = 0;
        for (VideoMetadataFixResult result : event.results()) {
            Media media = mediaMapper.selectById(result.pageId());
            if (media == null) {
                continue;
            }
            Chapter chapter = chapterMapper.selectById(media.getChapterId());
            if (chapter == null || !event.comicId().equals(chapter.getComicId())) {
                log.warn("忽略不属于事件漫画的媒体修复结果: comicId={}, mediaId={}", event.comicId(), result.pageId());
                continue;
            }
            if (result.width() != null) {
                media.setWidth(result.width());
            }
            if (result.height() != null) {
                media.setHeight(result.height());
            }
            if (result.duration() != null) {
                media.setDuration(result.duration());
            }
            if (result.container() != null) {
                media.setContainer(result.container());
            }
            if (result.videoCodec() != null) {
                media.setVideoCodec(result.videoCodec());
            }
            if (result.audioCodec() != null) {
                media.setAudioCodec(result.audioCodec());
            }
            mediaMapper.updateById(media);
            fixed++;
        }
        log.info("视频元数据修复完成: comicId={}, total={}, fixed={}", event.comicId(), event.results().size(), fixed);
    }
}
