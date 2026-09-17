package com.comicatlas.api.upload.infrastructure.persistence.repository;

import com.comicatlas.api.upload.application.port.out.UploadSessionPersistencePort;
import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadFile;
import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadSession;
import com.comicatlas.api.upload.infrastructure.persistence.mapper.UploadFileMapper;
import com.comicatlas.api.upload.infrastructure.persistence.mapper.UploadSessionMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 上传会话持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class UploadSessionPersistencePortAdapter implements UploadSessionPersistencePort {
    private final UploadSessionMapper sessionMapper;
    private final UploadFileMapper fileMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override public Comic findComic(Long comicId) { return comicMapper.selectById(comicId); }
    @Override public Chapter findChapter(Long chapterId) { return chapterMapper.selectById(chapterId); }
    @Override public Media findMedia(Long mediaId) { return mediaMapper.selectById(mediaId); }
    @Override public List<Media> findMediaByChapter(Long chapterId) { return mediaMapper.selectByChapterId(chapterId); }
    @Override public UploadSession findBySessionId(String sessionId) { return sessionMapper.selectBySessionId(sessionId); }
    @Override public UploadSession findById(Long sessionId) { return sessionMapper.selectById(sessionId); }
    @Override public List<UploadSession> findExpiredActive(LocalDateTime now) { return sessionMapper.selectExpiredActive(now); }
    @Override public List<UploadFile> findFiles(Long sessionId) { return fileMapper.selectBySessionId(sessionId); }
    @Override public UploadFile findFile(Long sessionId, String fileId) {
        return fileMapper.selectBySessionIdAndFileId(sessionId, fileId);
    }
    @Override public void insertSession(UploadSession session) { sessionMapper.insert(session); }
    @Override public void insertFile(UploadFile file) { fileMapper.insert(file); }
    @Override public void insertMedia(Media media) { mediaMapper.insert(media); }
    @Override public int bindMedia(Long fileId, Long mediaId) { return fileMapper.bindMedia(fileId, mediaId); }
    @Override public int freezeForVerification(Long sessionId) { return sessionMapper.freezeForVerification(sessionId); }
    @Override public int restoreActive(Long sessionId) { return sessionMapper.restoreActiveFromVerification(sessionId); }
    @Override public void updateSession(UploadSession session) { sessionMapper.updateById(session); }
    @Override public int deleteFiles(Long sessionId) { return fileMapper.deleteBySessionId(sessionId); }
    @Override public int deleteSession(Long sessionId) { return sessionMapper.deleteById(sessionId); }
}
