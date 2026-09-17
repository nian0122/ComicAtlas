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
    @Override public UploadSessionPersistencePort.SessionSnapshot findBySessionId(String sessionId) {
        return toSnapshot(sessionMapper.selectBySessionId(sessionId));
    }
    @Override public UploadSessionPersistencePort.SessionSnapshot findById(Long sessionId) {
        return toSnapshot(sessionMapper.selectById(sessionId));
    }
    @Override public List<UploadSessionPersistencePort.SessionSnapshot> findExpiredActive(LocalDateTime now) {
        return sessionMapper.selectExpiredActive(now).stream().map(this::toSnapshot).toList();
    }
    @Override public List<UploadSessionPersistencePort.FileSnapshot> findFiles(Long sessionId) {
        return fileMapper.selectBySessionId(sessionId).stream().map(this::toSnapshot).toList();
    }
    @Override public UploadSessionPersistencePort.FileSnapshot findFile(Long sessionId, String fileId) {
        return toSnapshot(fileMapper.selectBySessionIdAndFileId(sessionId, fileId));
    }
    @Override public Long insertSession(UploadSessionPersistencePort.CreateSessionCommand command) {
        UploadSession session = new UploadSession();
        session.setSessionId(command.sessionId());
        session.setComicId(command.comicId());
        session.setChapterId(command.chapterId());
        session.setReplaceMediaId(command.replaceMediaId());
        session.setStatus(command.status());
        session.setTotalBytes(command.totalBytes());
        session.setTotalFiles(command.totalFiles());
        session.setExpiresAt(command.expiresAt());
        sessionMapper.insert(session);
        return session.getId();
    }
    @Override public Long insertFile(UploadSessionPersistencePort.CreateFileCommand command) {
        UploadFile file = new UploadFile();
        file.setSessionId(command.sessionId());
        file.setFileId(command.fileId());
        file.setOriginalName(command.originalName());
        file.setContentType(command.contentType());
        file.setSizeBytes(command.sizeBytes());
        file.setSha256(command.sha256());
        file.setStorageName(command.storageName());
        file.setReceivedBytes(command.receivedBytes());
        file.setReceivedRanges(command.receivedRanges());
        fileMapper.insert(file);
        return file.getId();
    }
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
    @Override public void updateSession(UploadSessionPersistencePort.UpdateSessionCommand command) {
        UploadSession session = new UploadSession();
        session.setId(command.id());
        session.setStatus(command.status());
        session.setCompletedAt(command.completedAt());
        sessionMapper.updateById(session);
    }
    @Override public int deleteFiles(Long sessionId) { return fileMapper.deleteBySessionId(sessionId); }
    @Override public int deleteSession(Long sessionId) { return sessionMapper.deleteById(sessionId); }

    private UploadSessionPersistencePort.SessionSnapshot toSnapshot(UploadSession session) {
        return session == null ? null : new UploadSessionPersistencePort.SessionSnapshot(session.getId(),
                session.getSessionId(), session.getComicId(), session.getChapterId(), session.getReplaceMediaId(),
                session.getStatus(), session.getTotalBytes(), session.getTotalFiles(), session.getExpiresAt(),
                session.getCompletedAt());
    }

    private UploadSessionPersistencePort.FileSnapshot toSnapshot(UploadFile file) {
        return file == null ? null : new UploadSessionPersistencePort.FileSnapshot(file.getId(), file.getSessionId(),
                file.getFileId(), file.getOriginalName(), file.getContentType(), file.getSizeBytes(), file.getSha256(),
                file.getStorageName(), file.getReceivedBytes(), file.getReceivedRanges());
    }
}
