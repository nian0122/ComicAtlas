package com.comicatlas.api.upload.application.port.out;

import com.comicatlas.api.upload.domain.UploadSessionStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 上传会话应用服务访问会话、文件及媒体持久化的输出端口。 */
public interface UploadSessionPersistencePort {
    ComicSnapshot findComic(Long comicId);
    ChapterSnapshot findChapter(Long chapterId);
    MediaSnapshot findMedia(Long mediaId);
    List<MediaSnapshot> findMediaByChapter(Long chapterId);
    SessionSnapshot findBySessionId(String sessionId);
    SessionSnapshot findById(Long sessionId);
    List<SessionSnapshot> findExpiredActive(LocalDateTime now);
    List<FileSnapshot> findFiles(Long sessionId);
    FileSnapshot findFile(Long sessionId, String fileId);
    Long insertSession(CreateSessionCommand command);
    Long insertFile(CreateFileCommand command);
    Long insertMedia(MediaCreateCommand command);
    int bindMedia(Long fileId, Long mediaId);
    int freezeForVerification(Long sessionId);
    int restoreActive(Long sessionId);
    void updateSession(UpdateSessionCommand command);
    int deleteFiles(Long sessionId);
    int deleteSession(Long sessionId);

    record ComicSnapshot(Long id, ComicStatus status) {
    }

    record ChapterSnapshot(Long id, Long comicId) {
    }

    record MediaSnapshot(Long id, Long chapterId, Integer pageNumber, MediaLifecycleStatus status) {
    }

    record MediaCreateCommand(Long chapterId, Integer pageNumber, String hqRoot, String hqPath,
                              String mediaType, Long hqSize) {
    }

    record SessionSnapshot(Long id, String sessionId, Long comicId, Long chapterId, Long replaceMediaId,
                           UploadSessionStatus status, Long totalBytes, Integer totalFiles,
                           LocalDateTime expiresAt, LocalDateTime completedAt) {
    }

    record FileSnapshot(Long id, Long sessionId, String fileId, String originalName, String contentType,
                        Long sizeBytes, String sha256, String storageName, Long receivedBytes,
                        String receivedRanges) {
    }

    record CreateSessionCommand(String sessionId, Long comicId, Long chapterId, Long replaceMediaId,
                                UploadSessionStatus status, Long totalBytes, Integer totalFiles,
                                LocalDateTime expiresAt) {
    }

    record CreateFileCommand(Long sessionId, String fileId, String originalName, String contentType,
                             Long sizeBytes, String sha256, String storageName, Long receivedBytes,
                             String receivedRanges) {
    }

    record UpdateSessionCommand(Long id, UploadSessionStatus status, LocalDateTime completedAt) {
    }
}
