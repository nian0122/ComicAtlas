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
 * <p>
 * 预留接口能力：媒体上传/替换功能契约已实现且测试可用（见 MediaUploadManagementIT），
 * 但当前无前端页面入口，不属于漫画导入主流程。
 */
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadSessionService uploadSessionService;

    /**
     * 创建分块上传会话。
     * <p>
     * 校验目标漫画/章节、文件数与单文件/会话大小上限及磁盘剩余空间，
     * 为每个文件生成服务端 storageName，后续分片均在该会话内上传。
     *
     * @param request 会话创建请求（目标章节、文件名列表、总大小等）
     * @return 会话信息（sessionId 与各文件 storageName）
     */
    @PostMapping("/sessions")
    public Result<CreateUploadSessionResponse> createSession(
            @Valid @RequestBody CreateUploadSessionRequest request) {
        return Result.ok(uploadSessionService.create(request));
    }

    /**
     * 查询上传会话状态。
     * <p>
     * 返回各文件当前已接收字节数，供前端断点续传与进度展示；
     * 会话过期（24h 未完成）后查询将返回已过期状态。
     *
     * @param sessionId 会话 ID
     * @return 会话状态（各文件已接收字节数等）
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<UploadSessionStatusResponse> status(@PathVariable String sessionId) {
        return Result.ok(uploadSessionService.status(sessionId));
    }

    /**
     * 上传单个分片（原始字节流）。
     * <p>
     * 分片以 Content-Range 定位写入 STAGING，服务端流式处理不缓冲到内存；
     * 携带 X-Sha256 头时校验分片完整性，超范围或已接收的分片幂等返回当前进度。
     *
     * @param sessionId   会话 ID
     * @param fileId      会话内文件 ID
     * @param contentRange 分片字节范围（如 bytes=0-16777215）
     * @param chunkSha256 分片 SHA-256（可选，用于完整性校验）
     * @param request     HTTP 请求（读取分片输入流）
     * @return 分片接收结果（累计已接收字节数）
     * @throws IOException 分片流式写入失败
     */
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

    /**
     * 完成会话并提交。
     * <p>
     * 校验分片完整性与文件魔数后，预建 STAGING media 行并创建媒体上传管理任务，
     * 由 Worker 将文件搬入目标目录；未完整上传或会话已提交则返回业务错误。
     *
     * @param sessionId 会话 ID
     * @return 提交结果（管理任务 ID 与 media ID 列表）
     */
    @PostMapping("/sessions/{sessionId}/complete")
    public Result<UploadCompleteResponse> complete(@PathVariable String sessionId) {
        return Result.ok(uploadSessionService.complete(sessionId));
    }

    /**
     * 取消上传会话并清理 STAGING 文件。
     * <p>
     * 已 complete 的会话不允许取消，返回冲突错误。
     *
     * @param sessionId 会话 ID
     * @return 空结果
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> cancel(@PathVariable String sessionId) {
        uploadSessionService.cancel(sessionId);
        return Result.ok();
    }
}
