package com.comicatlas.api.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.upload.dto.*;
import com.comicatlas.api.upload.entity.UploadFile;
import com.comicatlas.api.upload.entity.UploadSession;
import com.comicatlas.api.upload.mapper.UploadFileMapper;
import com.comicatlas.api.upload.mapper.UploadSessionMapper;
import com.comicatlas.common.enums.TaskType;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 上传会话编排服务。
 * <p>
 * create → 校验目标/限制/磁盘空间/文件名，生成服务端 storageName；
 * uploadChunk → 委托存储层流式写分片；complete → 校验完整性与魔数后
 * 预建 STAGING media rows + 创建管理任务 + 同事务 Outbox 发布命令；
 * cancel/expire → 清理 STAGING 文件与会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadSessionService {

    private static final String EXCHANGE = "comic.management";
    private static final String ROUTING_REQUEST = "command.requested";

    private final UploadSessionMapper sessionMapper;
    private final UploadFileMapper fileMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final UploadProperties uploadProperties;
    private final UploadStorageService storageService;
    private final MediaTypeDetector mediaTypeDetector;
    private final ManagementTaskService managementTaskService;
    private final OutboxService outboxService;

    // ======================== 创建 ========================

    @Transactional
    public CreateUploadSessionResponse create(CreateUploadSessionRequest request) {
        Long comicId = request.getComicId();
        Long chapterId = request.getChapterId();
        Chapter chapter = validateTarget(comicId, chapterId, request.getReplaceMediaId());

        List<CreateUploadSessionRequest.FileManifest> manifest = request.getFiles();
        if (manifest.size() > uploadProperties.getMaxFiles()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "文件数超出上限: " + manifest.size() + " > " + uploadProperties.getMaxFiles());
        }
        long totalBytes = 0;
        for (var fm : manifest) {
            if (fm.getSize() > uploadProperties.getMaxFileSize()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + fm.getName() + " 超出单文件上限: " + fm.getSize() + " > "
                                + uploadProperties.getMaxFileSize());
            }
            totalBytes += fm.getSize();
            if (fm.getSha256() == null || !fm.getSha256().matches("^[0-9a-fA-F]{64}$")) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "文件 " + fm.getName() + " SHA-256 格式非法");
            }
            mediaTypeDetector.validateContentType(fm.getContentType());
            mediaTypeDetector.validateAndExtractExtension(fm.getName());
        }
        if (totalBytes > uploadProperties.getMaxSessionSize()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "会话总大小超出上限: " + totalBytes + " > " + uploadProperties.getMaxSessionSize());
        }
        storageService.ensureEnoughFreeSpace(totalBytes);

        UploadSession session = new UploadSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setComicId(comicId);
        session.setChapterId(chapterId);
        session.setReplaceMediaId(request.getReplaceMediaId());
        session.setStatus(UploadSessionStatus.ACTIVE.name());
        session.setTotalBytes(totalBytes);
        session.setTotalFiles(manifest.size());
        session.setExpiresAt(LocalDateTime.now().plus(uploadProperties.getSessionTtl()));
        sessionMapper.insert(session);
        storageService.ensureStagingDir(session);

        List<UploadFileResponse> fileResponses = new ArrayList<>();
        for (var fm : manifest) {
            String ext = mediaTypeDetector.validateAndExtractExtension(fm.getName());
            UploadFile uf = new UploadFile();
            uf.setSessionId(session.getId());
            uf.setFileId(fm.getFileId());
            uf.setOriginalName(fm.getName());
            uf.setContentType(fm.getContentType());
            uf.setSizeBytes(fm.getSize());
            uf.setSha256(fm.getSha256().toLowerCase(Locale.ROOT));
            uf.setStorageName(UUID.randomUUID().toString() + "." + ext);
            uf.setReceivedBytes(0L);
            uf.setReceivedRanges(null);
            fileMapper.insert(uf);

            UploadFileResponse fr = new UploadFileResponse();
            fr.setFileId(uf.getFileId());
            fr.setStorageName(uf.getStorageName());
            fr.setReceivedBytes(0);
            fr.setSizeBytes(uf.getSizeBytes());
            fr.setComplete(false);
            fileResponses.add(fr);
        }

        CreateUploadSessionResponse resp = new CreateUploadSessionResponse();
        resp.setSessionId(session.getSessionId());
        resp.setChunkSize(uploadProperties.getChunkSize());
        resp.setExpiresAt(session.getExpiresAt());
        resp.setTotalBytes(totalBytes);
        resp.setFiles(fileResponses);
        log.info("创建上传会话: sessionId={}, comicId={}, chapterId={}, files={}, bytes={}",
                session.getSessionId(), comicId, chapterId, manifest.size(), totalBytes);
        return resp;
    }

    private Chapter validateTarget(Long comicId, Long chapterId, Long replaceMediaId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        if (Set.of("DELETED", "DELETING", "TRASHED", "PURGING", "RESTORING").contains(comic.getStatus())) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "漫画状态 " + comic.getStatus() + " 不允许上传媒体");
        }
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getComicId().equals(comicId)) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不属于该漫画: " + chapterId);
        }
        if (replaceMediaId != null) {
            Media media = mediaMapper.selectById(replaceMediaId);
            if (media == null || !media.getChapterId().equals(chapterId)) {
                throw new BusinessException(HttpStatusCodes.NOT_FOUND, "替换目标媒体不存在或不属于该章节: " + replaceMediaId);
            }
            if (!"READY".equals(media.getStatus())) {
                throw new BusinessException(HttpStatusCodes.CONFLICT, "替换目标媒体状态 " + media.getStatus() + " 不允许替换");
            }
        }
        return chapter;
    }

    // ======================== 查询 ========================

    public UploadSession getBySessionId(String sessionId) {
        UploadSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSession>().eq(UploadSession::getSessionId, sessionId));
        if (session == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        return session;
    }

    public List<UploadFile> filesOf(UploadSession session) {
        return fileMapper.selectList(
                new LambdaQueryWrapper<UploadFile>()
                        .eq(UploadFile::getSessionId, session.getId())
                        .orderByAsc(UploadFile::getId));
    }

    public UploadSessionStatusResponse status(String sessionId) {
        UploadSession session = getBySessionId(sessionId);
        UploadSessionStatusResponse resp = new UploadSessionStatusResponse();
        resp.setSessionId(session.getSessionId());
        resp.setStatus(session.getStatus());
        resp.setTotalBytes(session.getTotalBytes());
        resp.setTotalFiles(session.getTotalFiles());
        resp.setExpiresAt(session.getExpiresAt());
        resp.setCompletedAt(session.getCompletedAt());
        resp.setFiles(filesOf(session).stream().map(this::toFileResponse).toList());
        return resp;
    }

    private UploadFileResponse toFileResponse(UploadFile uf) {
        UploadFileResponse fr = new UploadFileResponse();
        fr.setFileId(uf.getFileId());
        fr.setStorageName(uf.getStorageName());
        fr.setReceivedBytes(uf.getReceivedBytes() != null ? uf.getReceivedBytes() : 0);
        fr.setSizeBytes(uf.getSizeBytes());
        fr.setComplete(RangeTracker.isFullyReceived(uf.getReceivedRanges(), uf.getSizeBytes()));
        fr.setReceivedRanges(uf.getReceivedRanges() != null ? uf.getReceivedRanges() : "");
        return fr;
    }

    // ======================== 分片上传 ========================

    public UploadChunkResponse uploadChunk(String sessionId, String fileId,
                                           String contentRange, String chunkSha256,
                                           InputStream in) {
        UploadSession session = getBySessionId(sessionId);
        UploadFile file = fileMapper.selectOne(
                new LambdaQueryWrapper<UploadFile>()
                        .eq(UploadFile::getSessionId, session.getId())
                        .eq(UploadFile::getFileId, fileId));
        if (file == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "会话中不存在文件: " + fileId);
        }
        long[] range = parseContentRange(contentRange, file.getSizeBytes());
        String merged = storageService.writeChunk(session, file,
                range[0], range[1], range[2], chunkSha256, in);

        UploadChunkResponse resp = new UploadChunkResponse();
        resp.setFileId(file.getFileId());
        resp.setReceivedBytes(file.getReceivedBytes() != null ? file.getReceivedBytes() : 0);
        resp.setComplete(RangeTracker.isFullyReceived(merged, file.getSizeBytes()));
        resp.setReceivedRanges(merged);
        return resp;
    }

    private long[] parseContentRange(String contentRange, long declaredSize) {
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "缺少或非法 Content-Range 头: " + contentRange);
        }
        String spec = contentRange.substring("bytes ".length()).trim();
        int slash = spec.indexOf('/');
        if (slash <= 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range 头: " + contentRange);
        }
        try {
            long start = Long.parseLong(spec.substring(0, slash).split("-")[0]);
            long end = Long.parseLong(spec.substring(0, slash).split("-")[1]);
            long total = Long.parseLong(spec.substring(slash + 1));
            if (total != declaredSize) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "Content-Range 总大小与清单不符: " + total + " != " + declaredSize);
            }
            if (start < 0 || end < start || end >= total) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range: " + spec);
            }
            return new long[]{start, end, total};
        } catch (NumberFormatException e) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range 头: " + contentRange);
        }
    }

    // ======================== complete ========================

    @Transactional
    public UploadCompleteResponse complete(String sessionId) {
        UploadSession session = getBySessionId(sessionId);
        if (!UploadSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话状态 " + session.getStatus() + " 不允许 complete");
        }
        List<UploadFile> files = filesOf(session);
        if (files.isEmpty()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "会话为空，无文件可提交");
        }

        List<MediaTypeDetector.Detection> detections = new ArrayList<>();
        List<Long> mediaIds = new ArrayList<>();
        for (UploadFile uf : files) {
            if (!RangeTracker.isFullyReceived(uf.getReceivedRanges(), uf.getSizeBytes())) {
                List<long[]> missing = RangeTracker.missingRanges(uf.getReceivedRanges(), uf.getSizeBytes());
                String miss = missing.stream().map(r -> r[0] + "-" + r[1]).collect(Collectors.joining(";"));
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "文件 " + uf.getFileId() + " 未完整接收，缺失区间: " + miss);
            }
            Path staging = storageService.stagingPath(session, uf);
            String actualSha = computeSha256(staging);
            if (!uf.getSha256().equalsIgnoreCase(actualSha)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + uf.getFileId() + " 总校验失败: 声明=" + uf.getSha256() + " 实际=" + actualSha);
            }
            String ext = mediaTypeDetector.validateAndExtractExtension(uf.getStorageName());
            detections.add(mediaTypeDetector.detect(staging, ext));
        }

        boolean replace = session.getReplaceMediaId() != null;
        TaskType op = replace ? TaskType.MEDIA_REPLACE : TaskType.MEDIA_UPLOAD;

        if (replace) {
            // 替换：不预建新 media，直接创建管理任务指向会话
            mediaIds.add(session.getReplaceMediaId());
        } else {
            // 预建 STAGING media rows（追加到章节末尾 pageNumber）
            int nextPage = nextPageNumber(session.getChapterId());
            for (int i = 0; i < files.size(); i++) {
                UploadFile uf = files.get(i);
                MediaTypeDetector.Detection det = detections.get(i);
                Media media = new Media();
                media.setChapterId(session.getChapterId());
                media.setPageNumber(nextPage + i);
                media.setHqRoot("HQ");
                media.setHqPath(session.getComicId() + "/" + session.getChapterId() + "/" + uf.getStorageName());
                media.setHqStatus("PENDING");
                media.setLqStatus("NOT_GENERATED");
                media.setTranscodeStatus("NOT_NEEDED");
                media.setStatus("STAGING");
                media.setMediaType(det.mediaType());
                media.setFileSize(uf.getSizeBytes());
                media.setVersion(1);
                mediaMapper.insert(media);

                fileMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UploadFile>()
                        .eq(UploadFile::getId, uf.getId())
                        .set(UploadFile::getMediaId, media.getId()));
                mediaIds.add(media.getId());
            }
        }

        String idempotencyKey = "upload:" + session.getSessionId();
        ManagementTaskResponse task = managementTaskService.createTask(
                buildTaskRequest(op, session), idempotencyKey,
                "{\"session\":\"" + session.getSessionId() + "\"}");

        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(task.getId());
        for (ManagementTaskItemResponse item : items) {
            outboxService.enqueue(new ManagementCommandRequestedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    item.getTaskId(), item.getId(), item.getAttempt(),
                    op.name(), "UPLOAD_SESSION", session.getId()),
                    EXCHANGE, ROUTING_REQUEST,
                    item.getTaskId(), item.getId(), item.getAttempt());
        }

        session.setStatus(UploadSessionStatus.COMPLETED.name());
        session.setCompletedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        UploadCompleteResponse resp = new UploadCompleteResponse();
        resp.setTaskId(task.getId());
        resp.setTaskType(op.name());
        resp.setStatus(task.getStatus().name());
        resp.setItemCount(items.size());
        resp.setMediaIds(mediaIds);
        log.info("上传会话 complete: sessionId={}, op={}, taskId={}, mediaIds={}",
                sessionId, op, task.getId(), mediaIds);
        return resp;
    }

    private CreateManagementTaskRequest buildTaskRequest(TaskType op, UploadSession session) {
        CreateManagementTaskRequest req = new CreateManagementTaskRequest();
        req.setTaskType(op);
        req.setOperation("媒体上传" + (op == TaskType.MEDIA_REPLACE ? "替换" : ""));
        req.setTargetType("UPLOAD_SESSION");
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("UPLOAD_SESSION");
        target.setTargetId(session.getId());
        target.setOperationType(op);
        req.setTargets(List.of(target));
        return req;
    }

    private int nextPageNumber(Long chapterId) {
        return mediaMapper.selectList(
                        new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId))
                .stream()
                .map(Media::getPageNumber)
                .filter(n -> n != null)
                .max(Comparator.naturalOrder())
                .orElse(0);
    }

    private String computeSha256(Path file) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "计算文件 SHA-256 失败: " + e.getMessage());
        }
    }

    // ======================== 取消/过期 ========================

    @Transactional
    public void cancel(String sessionId) {
        UploadSession session = getBySessionId(sessionId);
        if (UploadSessionStatus.CANCELLED.name().equals(session.getStatus())) {
            return;
        }
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话已 complete，无法取消");
        }
        storageService.deleteStagingDir(session);
        fileMapper.delete(new LambdaQueryWrapper<UploadFile>()
                .eq(UploadFile::getSessionId, session.getId()));
        session.setStatus(UploadSessionStatus.CANCELLED.name());
        sessionMapper.updateById(session);
        log.info("取消上传会话: sessionId={}", sessionId);
    }

    /**
     * 过期清理：24h 未完成的 ACTIVE 会话标记 EXPIRED 并删除 STAGING 文件。
     */
    @Transactional
    public int expireExpiredSessions() {
        List<UploadSession> expired = sessionMapper.selectList(
                new LambdaQueryWrapper<UploadSession>()
                        .eq(UploadSession::getStatus, UploadSessionStatus.ACTIVE.name())
                        .lt(UploadSession::getExpiresAt, LocalDateTime.now()));
        for (UploadSession session : expired) {
            storageService.deleteStagingDir(session);
            fileMapper.delete(new LambdaQueryWrapper<UploadFile>()
                    .eq(UploadFile::getSessionId, session.getId()));
            session.setStatus(UploadSessionStatus.EXPIRED.name());
            sessionMapper.updateById(session);
        }
        if (!expired.isEmpty()) {
            log.info("过期清理上传会话: {}", expired.size());
        }
        return expired.size();
    }

    // ======================== 会话清理（complete 成功/失败后） ========================

    /**
     * 删除会话的 STAGING 文件与 upload_file 行（Worker 成功搬移后调用）。
     */
    @Transactional
    public void cleanupSessionAfterProcessed(Long sessionId) {
        UploadSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        storageService.deleteStagingDir(session);
        fileMapper.delete(new LambdaQueryWrapper<UploadFile>()
                .eq(UploadFile::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        log.info("会话处理完成，清理 STAGING 与会话行: sessionId={}", session.getSessionId());
    }
}
