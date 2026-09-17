package com.comicatlas.api.trash.application.port.out;

import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;

/** 回收对账服务访问实体状态的输出端口。 */
public interface TrashReconciliationPersistencePort {
    Comic findComic(Long comicId);
    Chapter findChapter(Long chapterId);
    Media findMedia(Long mediaId);
    void updateComic(Comic comic);
    void updateChapter(Chapter chapter);
    void updateMedia(Media media);
}
