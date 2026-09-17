package com.comicatlas.api.upload.infrastructure.persistence.repository;

import com.comicatlas.api.upload.application.port.out.UploadCompletionPersistencePort;
import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadSession;
import com.comicatlas.api.upload.infrastructure.persistence.mapper.UploadSessionMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 上传完成持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class UploadCompletionPersistencePortAdapter implements UploadCompletionPersistencePort {
    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final UploadSessionMapper uploadSessionMapper;

    @Override public int applyMediaCompleted(Media media, boolean replace) {
        return mediaMapper.applyUploadCompleted(media, replace);
    }
    @Override public UploadSession findSession(Long sessionId) { return uploadSessionMapper.selectById(sessionId); }
    @Override public Chapter findChapter(Long chapterId) { return chapterMapper.selectById(chapterId); }
    @Override public int markSessionFailed(Long sessionId) { return uploadSessionMapper.markFailed(sessionId); }
}
