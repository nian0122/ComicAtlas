package com.comicatlas.api.upload.application.port.out;

import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;

import java.util.List;

/** 媒体管理应用服务访问章节和媒体持久化的输出端口。 */
public interface MediaManagementPersistencePort {
    Chapter findChapter(Long chapterId);
    List<Media> findMediaByChapter(Long chapterId);
    Media findMedia(Long mediaId);
    int movePageNumbersToTemporaryNegative(Long chapterId);
    int updateMedia(Media media);
}
