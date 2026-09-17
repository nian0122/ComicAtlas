package com.comicatlas.api.upload.application.port.out;

import java.util.List;

/** 媒体管理应用服务访问章节和媒体持久化的输出端口。 */
public interface MediaManagementPersistencePort {
    ChapterSnapshot findChapter(Long chapterId);
    List<MediaSnapshot> findMediaByChapter(Long chapterId);
    MediaSnapshot findMedia(Long mediaId);
    int movePageNumbersToTemporaryNegative(Long chapterId);
    int updateMedia(MediaPageNumberUpdateCommand command);

    record ChapterSnapshot(Long id) {
    }

    record MediaSnapshot(Long id, Long chapterId, Integer pageNumber, Integer version) {
    }

    record MediaPageNumberUpdateCommand(Long id, Integer pageNumber, Integer version) {
    }
}
