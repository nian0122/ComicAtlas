package com.comicatlas.api.upload.application.port.out;

import com.comicatlas.api.upload.domain.UploadSessionStatus;

import java.io.InputStream;
import java.nio.file.Path;

/** 上传临时文件存储端口。应用层只依赖上传所需的存储能力。 */
public interface UploadStoragePort {

    void ensureEnoughFreeSpace(long requiredBytes);

    void ensureStagingDir(String sessionId);

    Path stagingPath(String sessionId, String storageName);

    WriteChunkResult writeChunk(String sessionId, Long fileId, String storageName, UploadSessionStatus sessionStatus,
                                long fileSize, String receivedRanges, long start, long end, long total,
                                String chunkSha256, InputStream inputStream);

    void deleteStagingDir(String sessionId);

    record WriteChunkResult(long receivedBytes, String receivedRanges) {
    }
}
