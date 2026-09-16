package com.comicatlas.api.upload.service;

import com.comicatlas.api.upload.dto.CreateUploadSessionRequest;
import com.comicatlas.api.upload.dto.CreateUploadSessionResponse;
import com.comicatlas.api.upload.dto.UploadChunkResponse;
import com.comicatlas.api.upload.dto.UploadCompleteResponse;
import com.comicatlas.api.upload.dto.UploadSessionStatusResponse;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import java.util.List;
import java.io.InputStream;

/** 上传会话编排服务契约。 */
public interface UploadSessionService {
    CreateUploadSessionResponse create(CreateUploadSessionRequest request);
    UploadSession getBySessionId(String sessionId);
    List<UploadFile> filesOf(UploadSession session);
    UploadSessionStatusResponse status(String sessionId);
    UploadChunkResponse uploadChunk(String sessionId, String fileId, String contentRange,
                                    String chunkSha256, InputStream input);
    UploadCompleteResponse complete(String sessionId);
    void cancel(String sessionId);
    int expireExpiredSessions();
    void cleanupSessionAfterProcessed(Long sessionId);
}
