package com.comicatlas.api.media.application.service.impl;

import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;
import com.comicatlas.common.event.payload.VideoMetadataFixResult;
import com.comicatlas.api.media.application.port.in.VideoMetadataFixService;
import com.comicatlas.api.media.application.port.out.MediaMetadataPersistencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 视频元数据修复结果应用服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetadataFixServiceImpl implements VideoMetadataFixService {
    // 视频修复契约由应用服务公开，具体实现保持在媒体业务包内。
    private final MediaMetadataPersistencePort persistencePort;

    @Transactional
    public void apply(VideoMetadataFixCompletedEvent event) {
        int fixed = 0;
        for (VideoMetadataFixResult result : event.results()) {
            MediaMetadataPersistencePort.MediaSnapshot media = persistencePort.findMedia(result.pageId());
            if (media == null) {
                continue;
            }
            MediaMetadataPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(media.chapterId());
            if (chapter == null || !event.comicId().equals(chapter.comicId())) {
                log.warn("忽略不属于事件漫画的媒体修复结果: comicId={}, mediaId={}", event.comicId(), result.pageId());
                continue;
            }
            persistencePort.updateMedia(new MediaMetadataPersistencePort.MediaUpdateCommand(
                    media.id(), result.width(), result.height(), result.duration(), result.container(),
                    result.videoCodec(), result.audioCodec()));
            fixed++;
        }
        log.info("视频元数据修复完成: comicId={}, total={}, fixed={}", event.comicId(), event.results().size(), fixed);
    }
}
