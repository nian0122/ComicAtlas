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

    @Override public UploadSessionPersistencePort.ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new UploadSessionPersistencePort.ComicSnapshot(
                comic.getId(), comic.getStatus());
    }
    @Override public UploadSessionPersistencePort.ChapterSnapshot findChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : new UploadSessionPersistencePort.ChapterSnapshot(
                chapter.getId(), chapter.getComicId());
    }
    @Override public UploadSessionPersistencePort.MediaSnapshot findMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        return media == null ? null : new UploadSessionPersistencePort.MediaSnapshot(
                media.getId(), media.getChapterId(), media.getPageNumber(), media.getStatus());
    }
    @Override public List<UploadSessionPersistencePort.MediaSnapshot> findMediaByChapter(Long chapterId) {
        return mediaMapper.selectByChapterId(chapterId).stream()
                .map(media -> new UploadSessionPersistencePort.MediaSnapshot(
                        media.getId(), media.getChapterId(), media.getPageNumber(), media.getStatus()))
                .toList();
    }
    @Override public UploadSession findBySessionId(String sessionId) { return sessionMapper.selectBySessionId(sessionId); }
    @Override public UploadSession findById(Long sessionId) { return sessionMapper.selectById(sessionId); }
    @Override public List<UploadSession> findExpiredActive(LocalDateTime now) { return sessionMapper.selectExpiredActive(now); }
    @Override public List<UploadFile> findFiles(Long sessionId) { return fileMapper.selectBySessionId(sessionId); }
    @Override public UploadFile findFile(Long sessionId, String fileId) {
        return fileMapper.selectBySessionIdAndFileId(sessionId, fileId);
    }
    @Override public void insertSession(UploadSession session) { sessionMapper.insert(session); }
    @Override public void insertFile(UploadFile file) { fileMapper.insert(file); }
    @Override public Long insertMedia(UploadSessionPersistencePort.MediaCreateCommand command) {
        Media media = new Media();
        media.setChapterId(command.chapterId());
        media.setPageNumber(command.pageNumber());
        media.setHqRoot(command.hqRoot());
        media.setHqPath(command.hqPath());
        media.setHqStatus(com.comicatlas.contract.common.enums.HqStatus.PENDING);
        media.setLqStatus(com.comicatlas.contract.common.enums.LqStatus.NOT_GENERATED);
        media.setTranscodeStatus(com.comicatlas.contract.common.enums.TranscodeStatus.NOT_NEEDED);
        media.setStatus(com.comicatlas.contract.common.enums.MediaLifecycleStatus.STAGING);
        media.setMediaType(command.mediaType());
        media.setHqSize(command.hqSize());
        media.setVersion(1);
        mediaMapper.insert(media);
        return media.getId();
    }
    @Override public int bindMedia(Long fileId, Long mediaId) { return fileMapper.bindMedia(fileId, mediaId); }
    @Override public int freezeForVerification(Long sessionId) { return sessionMapper.freezeForVerification(sessionId); }
    @Override public int restoreActive(Long sessionId) { return sessionMapper.restoreActiveFromVerification(sessionId); }
    @Override public void updateSession(UploadSession session) { sessionMapper.updateById(session); }
    @Override public int deleteFiles(Long sessionId) { return fileMapper.deleteBySessionId(sessionId); }
    @Override public int deleteSession(Long sessionId) { return sessionMapper.deleteById(sessionId); }
}
