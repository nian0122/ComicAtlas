package com.comicatlas.api.upload.application.port.out;

/** 上传完成处理访问媒体、章节和会话持久化的输出端口。 */
public interface UploadCompletionPersistencePort {
    int applyMediaCompleted(MediaCompletedCommand command, boolean replace);
    UploadSessionSnapshot findSession(Long sessionId);
    ChapterSnapshot findChapter(Long chapterId);
    int markSessionFailed(Long sessionId);

    record MediaCompletedCommand(Long id, Integer width, Integer height, Long hqSize,
                                 String mediaType, java.math.BigDecimal duration, String container,
                                 String videoCodec, String audioCodec, String hqRoot, String hqPath) {
    }

    record UploadSessionSnapshot(Long id, Long chapterId) {
    }

    record ChapterSnapshot(Long id, Long comicId) {
    }
}
