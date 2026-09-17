package com.comicatlas.api.media.application.port.out;

import java.math.BigDecimal;

/** 媒体元数据应用服务访问媒体与章节持久化的输出端口。 */
public interface MediaMetadataPersistencePort {
    MediaSnapshot findMedia(Long mediaId);
    ChapterSnapshot findChapter(Long chapterId);
    int updateMedia(MediaUpdateCommand command);

    record MediaSnapshot(Long id, Long chapterId) {
    }

    record ChapterSnapshot(Long id, Long comicId) {
    }

    record MediaUpdateCommand(Long id, Integer width, Integer height, BigDecimal duration,
                              String container, String videoCodec, String audioCodec) {
    }
}
