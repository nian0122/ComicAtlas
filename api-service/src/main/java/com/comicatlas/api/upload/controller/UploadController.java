package com.comicatlas.api.upload.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.upload.UploadSessionService;
import com.comicatlas.api.upload.dto.CreateUploadSessionRequest;
import com.comicatlas.api.upload.dto.CreateUploadSessionResponse;
import com.comicatlas.api.upload.dto.UploadChunkResponse;
import com.comicatlas.api.upload.dto.UploadCompleteResponse;
import com.comicatlas.api.upload.dto.UploadSessionStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 分片上传会话端点。
 * <p>
 * 分片以原始字节流接收（Content-Range + X-Sha256 头），服务端流式写入 STAGING，
 * 不缓冲到内存。STAGING 不经 Nginx 暴露，不可下载。
 */
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadSessionService uploadSessionService;

    @PostMapping("/sessions")
    public Result<CreateUploadSessionResponse> createSession(
            @Valid @RequestBody CreateUploadSessionRequest request) {
        return Result.ok(uploadSessionService.create(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public Result<UploadSessionStatusResponse> status(@PathVariable String sessionId) {
        return Result.ok(uploadSessionService.status(sessionId));
    }

    @PutMapping("/sessions/{sessionId}/files/{fileId}")
    public Result<UploadChunkResponse> uploadChunk(
            @PathVariable String sessionId,
            @PathVariable String fileId,
            @RequestHeader(value = "Content-Range", required = false) String contentRange,
            @RequestHeader(value = "X-Sha256", required = false) String chunkSha256,
            HttpServletRequest request) throws IOException {
        UploadChunkResponse resp = uploadSessionService.uploadChunk(
                sessionId, fileId, contentRange, chunkSha256, request.getInputStream());
        return Result.ok(resp);
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public Result<UploadCompleteResponse> complete(@PathVariable String sessionId) {
        return Result.ok(uploadSessionService.complete(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> cancel(@PathVariable String sessionId) {
        uploadSessionService.cancel(sessionId);
        return Result.ok();
    }
}
