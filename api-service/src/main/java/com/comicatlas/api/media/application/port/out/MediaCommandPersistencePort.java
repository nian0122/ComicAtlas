package com.comicatlas.api.media.application.port.out;

import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import java.util.List;

/** 媒体命令服务的持久化端口。 */
public interface MediaCommandPersistencePort {
    ComicSnapshot findComic(Long id);
    ChapterSnapshot findChapter(Long id);
    List<ChapterSnapshot> findChapters(Long comicId);
    List<MediaSnapshot> findImages(Long chapterId);
    List<MediaSnapshot> findDeletableImages(List<Long> chapterIds);
    long countDeletableImages(Long chapterId);
    List<MediaSnapshot> findVideosByChapters(List<Long> chapterIds);
    List<MediaSnapshot> findVideos(Long chapterId);
    MediaSnapshot findMedia(Long mediaId);
    int markLqQueued(Long chapterId);
    int markHqDeleteQueued(Long chapterId);
    int markHqDeleteQueued(List<Long> chapterIds);
    int markTranscodeQueued(Long mediaId);
    int markTranscodeNotNeeded(Long mediaId);

    record ComicSnapshot(Long id, ComicStatus status) {
    }

    record ChapterSnapshot(Long id) {
    }

    record MediaSnapshot(Long id, Long chapterId, Integer pageNumber, String mediaType, HqStatus hqStatus,
                         LqStatus lqStatus, TranscodeStatus transcodeStatus, Integer width, Integer height,
                         String videoCodec, String container) {
    }
}
