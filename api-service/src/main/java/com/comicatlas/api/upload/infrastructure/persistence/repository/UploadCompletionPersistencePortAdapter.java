package com.comicatlas.api.upload.infrastructure.persistence.repository;

import com.comicatlas.api.upload.application.port.out.UploadCompletionPersistencePort;
import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadSession;
import com.comicatlas.api.upload.infrastructure.persistence.mapper.UploadSessionMapper;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.entity.Chapter;
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

    @Override public int applyMediaCompleted(
            UploadCompletionPersistencePort.MediaCompletedCommand command, boolean replace) {
        Media media = new Media();
        media.setId(command.id());
        media.setWidth(command.width());
        media.setHeight(command.height());
        media.setHqSize(command.hqSize());
        media.setMediaType(command.mediaType());
        media.setDuration(command.duration());
        media.setContainer(command.container());
        media.setVideoCodec(command.videoCodec());
        media.setAudioCodec(command.audioCodec());
        media.setHqRoot(command.hqRoot());
        media.setHqPath(command.hqPath());
        return mediaMapper.applyUploadCompleted(media, replace);
    }
    @Override public UploadCompletionPersistencePort.UploadSessionSnapshot findSession(Long sessionId) {
        UploadSession session = uploadSessionMapper.selectById(sessionId);
        return session == null ? null : new UploadCompletionPersistencePort.UploadSessionSnapshot(
                session.getId(), session.getChapterId());
    }
    @Override public UploadCompletionPersistencePort.ChapterSnapshot findChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : new UploadCompletionPersistencePort.ChapterSnapshot(
                chapter.getId(), chapter.getComicId());
    }
    @Override public int markSessionFailed(Long sessionId) { return uploadSessionMapper.markFailed(sessionId); }
}
