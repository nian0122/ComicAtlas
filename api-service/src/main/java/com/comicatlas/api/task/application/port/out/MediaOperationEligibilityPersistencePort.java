package com.comicatlas.api.task.application.port.out;

import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;

import java.util.List;

/** 媒体操作资格计算所需的资产读取端口。 */
public interface MediaOperationEligibilityPersistencePort {
    ComicSnapshot findComic(Long comicId);
    List<ChapterSnapshot> findChapters(Long comicId);
    List<MediaSnapshot> findMediaByChapters(List<Long> chapterIds);
    List<MediaSnapshot> findMediaByChapter(Long chapterId);
    MediaSnapshot findMedia(Long mediaId);

    record ComicSnapshot(Long id, ComicStatus status) {
    }

    record ChapterSnapshot(Long id) {
    }

    record MediaSnapshot(Long id, Long chapterId, String mediaType, HqStatus hqStatus, LqStatus lqStatus,
                         TranscodeStatus transcodeStatus, Integer width, Integer height,
                         String videoCodec, String container, MediaLifecycleStatus status) {
    }
}
