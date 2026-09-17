package com.comicatlas.api.upload.application.port.out;

import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadFile;
import com.comicatlas.api.upload.infrastructure.persistence.entity.UploadSession;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.persistence.comic.entity.Media;

import java.time.LocalDateTime;
import java.util.List;

/** 上传会话应用服务访问会话、文件及媒体持久化的输出端口。 */
public interface UploadSessionPersistencePort {
    ComicSnapshot findComic(Long comicId);
    ChapterSnapshot findChapter(Long chapterId);
    MediaSnapshot findMedia(Long mediaId);
    List<MediaSnapshot> findMediaByChapter(Long chapterId);
    UploadSession findBySessionId(String sessionId);
    UploadSession findById(Long sessionId);
    List<UploadSession> findExpiredActive(LocalDateTime now);
    List<UploadFile> findFiles(Long sessionId);
    UploadFile findFile(Long sessionId, String fileId);
    void insertSession(UploadSession session);
    void insertFile(UploadFile file);
    void insertMedia(Media media);
    int bindMedia(Long fileId, Long mediaId);
    int freezeForVerification(Long sessionId);
    int restoreActive(Long sessionId);
    void updateSession(UploadSession session);
    int deleteFiles(Long sessionId);
    int deleteSession(Long sessionId);

    record ComicSnapshot(Long id, ComicStatus status) {
    }

    record ChapterSnapshot(Long id, Long comicId) {
    }

    record MediaSnapshot(Long id, Long chapterId, Integer pageNumber, MediaLifecycleStatus status) {
    }
}
