package com.comicatlas.api.upload.application.port.out;

import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadSession;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;

/** 上传完成处理访问媒体、章节和会话持久化的输出端口。 */
public interface UploadCompletionPersistencePort {
    int applyMediaCompleted(Media media, boolean replace);
    UploadSession findSession(Long sessionId);
    Chapter findChapter(Long chapterId);
    int markSessionFailed(Long sessionId);
}
