package com.comicatlas.api.upload.application.port.in;

import com.comicatlas.api.upload.interfaces.rest.dto.CreateUploadSessionRequest;
import com.comicatlas.api.upload.interfaces.rest.dto.CreateUploadSessionResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadChunkResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadCompleteResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadSessionStatusResponse;
import java.io.InputStream;

/** 上传会话编排服务契约。 */
public interface UploadSessionService {
    CreateUploadSessionResponse create(CreateUploadSessionRequest request);
    UploadSessionStatusResponse status(String sessionId);
    UploadChunkResponse uploadChunk(String sessionId, String fileId, String contentRange,
                                    String chunkSha256, InputStream input);
    UploadCompleteResponse complete(String sessionId);
    void cancel(String sessionId);
    int expireExpiredSessions();
    void cleanupSessionAfterProcessed(Long sessionId);
}
