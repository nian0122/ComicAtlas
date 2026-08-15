package com.comicatlas.api.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.upload.dto.CreateUploadSessionRequest;
import com.comicatlas.api.upload.dto.CreateUploadSessionResponse;
import com.comicatlas.api.upload.dto.UploadChunkResponse;
import com.comicatlas.api.upload.dto.UploadCompleteResponse;
import com.comicatlas.api.upload.dto.UploadFileResponse;
import com.comicatlas.api.upload.dto.UploadSessionStatusResponse;
import com.comicatlas.api.upload.entity.UploadFile;
import com.comicatlas.api.upload.entity.UploadSession;
import com.comicatlas.api.upload.mapper.UploadFileMapper;
import com.comicatlas.api.upload.mapper.UploadSessionMapper;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TaskType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * <p>
 * 预留接口能力：媒体上传/替换功能契约已实现且测试可用（见 MediaUploadManagementIT），
 * 但当前无前端页面入口，不属于漫画导入主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadSessionService {

    /** 管理命令交换器。 */
    private static final String MANAGEMENT_EXCHANGE = MqExchanges.MANAGEMENT;
    /** 管理命令请求路由键。 */
    private static final String COMMAND_REQUEST_ROUTING_KEY = MqRoutingKeys.COMMAND_REQUESTED;

    /** 上传会话目标类型标识（管理任务 targetType 契约值）。 */
    private static final String TARGET_TYPE_UPLOAD_SESSION = "UPLOAD_SESSION";
    /** SHA-256 十六进制格式校验：64 位小写/大写字母数字。 */
    private static final String SHA256_PATTERN = "^[0-9a-fA-F]{64}$";
    /** Content-Range 头的字节范围单位前缀。 */
    private static final String CONTENT_RANGE_PREFIX = "bytes ";
    /** SHA-256 计算读取缓冲区大小。 */
    private static final int SHA256_BUFFER_SIZE = 64 * 1024;
    /** 乐观锁初始版本。 */
    private static final int INITIAL_VERSION = 1;
    /** 禁止上传媒体的漫画终态集合（复用避免每次构造）。 */
    private static final Set<ComicStatus> NON_UPLOADABLE_STATUSES =
            Set.of(ComicStatus.DELETED, ComicStatus.DELETING, ComicStatus.TRASHED,
                    ComicStatus.PURGING, ComicStatus.RESTORING);

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
        validateTarget(comicId, chapterId, request.getReplaceMediaId());

        long totalBytes = validateManifest(request.getFiles());
        storageService.ensureEnoughFreeSpace(totalBytes);

        UploadSession session = new UploadSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setComicId(comicId);
        session.setChapterId(chapterId);
        session.setReplaceMediaId(request.getReplaceMediaId());
        session.setStatus(UploadSessionStatus.ACTIVE);
        session.setTotalBytes(totalBytes);
        session.setTotalFiles(request.getFiles().size());
        session.setExpiresAt(LocalDateTime.now().plus(uploadProperties.getSessionTtl()));
        sessionMapper.insert(session);
        storageService.ensureStagingDir(session);

        List<UploadFileResponse> fileResponses = insertFiles(session, request.getFiles());

        CreateUploadSessionResponse response = new CreateUploadSessionResponse();
        response.setSessionId(session.getSessionId());
        response.setChunkSize(uploadProperties.getChunkSize());
        response.setExpiresAt(session.getExpiresAt());
        response.setTotalBytes(totalBytes);
        response.setFiles(fileResponses);
        log.info("创建上传会话: sessionId={}, comicId={}, chapterId={}, files={}, bytes={}",
                session.getSessionId(), comicId, chapterId, request.getFiles().size(), totalBytes);
        return response;
    }

    /** 校验文件清单（数量/单文件大小/SHA-256/类型/扩展名），返回清单总字节数。 */
    private long validateManifest(List<CreateUploadSessionRequest.FileManifest> manifest) {
        if (manifest.size() > uploadProperties.getMaxFiles()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "文件数超出上限: " + manifest.size() + " > " + uploadProperties.getMaxFiles());
        }
        long totalBytes = 0;
        for (CreateUploadSessionRequest.FileManifest fileManifest : manifest) {
            if (fileManifest.getSize() > uploadProperties.getMaxFileSize()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + fileManifest.getName() + " 超出单文件上限: " + fileManifest.getSize() + " > "
                                + uploadProperties.getMaxFileSize());
            }
            totalBytes += fileManifest.getSize();
            if (fileManifest.getSha256() == null || !fileManifest.getSha256().matches(SHA256_PATTERN)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + fileManifest.getName() + " SHA-256 格式非法");
            }
            mediaTypeDetector.validateContentType(fileManifest.getContentType());
            mediaTypeDetector.validateAndExtractExtension(fileManifest.getName());
        }
        if (totalBytes > uploadProperties.getMaxSessionSize()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "会话总大小超出上限: " + totalBytes + " > " + uploadProperties.getMaxSessionSize());
        }
        return totalBytes;
    }

    /** 为清单中每个文件生成 storageName 并插入 upload_file 行，返回对外文件响应列表。 */
    private List<UploadFileResponse> insertFiles(UploadSession session,
                                                 List<CreateUploadSessionRequest.FileManifest> manifest) {
        List<UploadFileResponse> fileResponses = new ArrayList<>(manifest.size());
        for (CreateUploadSessionRequest.FileManifest fileManifest : manifest) {
            String ext = mediaTypeDetector.validateAndExtractExtension(fileManifest.getName());
            UploadFile uploadFile = new UploadFile();
            uploadFile.setSessionId(session.getId());
            uploadFile.setFileId(fileManifest.getFileId());
            uploadFile.setOriginalName(fileManifest.getName());
            uploadFile.setContentType(fileManifest.getContentType());
            uploadFile.setSizeBytes(fileManifest.getSize());
            uploadFile.setSha256(fileManifest.getSha256().toLowerCase(Locale.ROOT));
            uploadFile.setStorageName(UUID.randomUUID().toString() + "." + ext);
            uploadFile.setReceivedBytes(0L);
            uploadFile.setReceivedRanges(null);
            fileMapper.insert(uploadFile);

            UploadFileResponse fileResponse = new UploadFileResponse();
            fileResponse.setFileId(uploadFile.getFileId());
            fileResponse.setStorageName(uploadFile.getStorageName());
            fileResponse.setReceivedBytes(0);
            fileResponse.setSizeBytes(uploadFile.getSizeBytes());
            fileResponse.setComplete(false);
            fileResponses.add(fileResponse);
        }
        return fileResponses;
    }

    private Chapter validateTarget(Long comicId, Long chapterId, Long replaceMediaId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        if (NON_UPLOADABLE_STATUSES.contains(comic.getStatus())) {
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
            if (media.getStatus() != MediaLifecycleStatus.READY) {
                throw new BusinessException(HttpStatusCodes.CONFLICT, "替换目标媒体状态 " + media.getStatus() + " 不允许替换");
            }
        }
        return chapter;
    }

    // ======================== 查询 ========================

    /**
     * 按对外会话 ID 查询上传会话，不存在抛出 404。
     * <p>
     * 内部方法，返回数据库实体 {@link UploadSession}，禁止用于接口响应；对外使用 {@code dto/} 包对应 DTO/VO。
     *
     * @param sessionId 对外 opaque 会话 ID
     * @return 上传会话实体
     */
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
        UploadSessionStatusResponse response = new UploadSessionStatusResponse();
        response.setSessionId(session.getSessionId());
        response.setStatus(session.getStatus() == null ? null : session.getStatus().name());
        response.setTotalBytes(session.getTotalBytes());
        response.setTotalFiles(session.getTotalFiles());
        response.setExpiresAt(session.getExpiresAt());
        response.setCompletedAt(session.getCompletedAt());
        response.setFiles(filesOf(session).stream().map(this::toFileResponse).toList());
        return response;
    }

    private UploadFileResponse toFileResponse(UploadFile uploadFile) {
        UploadFileResponse fileResponse = new UploadFileResponse();
        fileResponse.setFileId(uploadFile.getFileId());
        fileResponse.setStorageName(uploadFile.getStorageName());
        fileResponse.setReceivedBytes(uploadFile.getReceivedBytes() != null ? uploadFile.getReceivedBytes() : 0);
        fileResponse.setSizeBytes(uploadFile.getSizeBytes());
        fileResponse.setComplete(RangeTracker.isFullyReceived(uploadFile.getReceivedRanges(), uploadFile.getSizeBytes()));
        fileResponse.setReceivedRanges(uploadFile.getReceivedRanges() != null ? uploadFile.getReceivedRanges() : "");
        return fileResponse;
    }

    // ======================== 分片上传 ========================

    public UploadChunkResponse uploadChunk(String sessionId, String fileId,
                                           String contentRange, String chunkSha256,
                                           InputStream input) {
        UploadSession session = getBySessionId(sessionId);
        UploadFile file = fileMapper.selectOne(
                new LambdaQueryWrapper<UploadFile>()
                        .eq(UploadFile::getSessionId, session.getId())
                        .eq(UploadFile::getFileId, fileId));
        if (file == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "会话中不存在文件: " + fileId);
        }
        long[] byteRange = parseContentRange(contentRange, file.getSizeBytes());
        String merged = storageService.writeChunk(session, file,
                byteRange[0], byteRange[1], byteRange[2], chunkSha256, input);

        UploadChunkResponse response = new UploadChunkResponse();
        response.setFileId(file.getFileId());
        response.setReceivedBytes(file.getReceivedBytes() != null ? file.getReceivedBytes() : 0);
        response.setComplete(RangeTracker.isFullyReceived(merged, file.getSizeBytes()));
        response.setReceivedRanges(merged);
        return response;
    }

    private long[] parseContentRange(String contentRange, long declaredSize) {
        if (contentRange == null || !contentRange.startsWith(CONTENT_RANGE_PREFIX)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "缺少或非法 Content-Range 头: " + contentRange);
        }
        String rangeSpec = contentRange.substring(CONTENT_RANGE_PREFIX.length()).trim();
        int slashIndex = rangeSpec.indexOf('/');
        if (slashIndex <= 0) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range 头: " + contentRange);
        }
        try {
            long start = Long.parseLong(rangeSpec.substring(0, slashIndex).split("-")[0]);
            long end = Long.parseLong(rangeSpec.substring(0, slashIndex).split("-")[1]);
            long total = Long.parseLong(rangeSpec.substring(slashIndex + 1));
            if (total != declaredSize) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "Content-Range 总大小与清单不符: " + total + " != " + declaredSize);
            }
            if (start < 0 || end < start || end >= total) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range: " + rangeSpec);
            }
            return new long[]{start, end, total};
        } catch (NumberFormatException ex) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "非法 Content-Range 头: " + contentRange);
        }
    }

    // ======================== complete ========================

    @Transactional
    public UploadCompleteResponse complete(String sessionId) {
        UploadSession session = getBySessionId(sessionId);
        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话状态 " + session.getStatus() + " 不允许 complete");
        }
        List<UploadFile> files = filesOf(session);
        if (files.isEmpty()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "会话为空，无文件可提交");
        }

        List<MediaTypeDetector.Detection> detections = verifyUploadedFiles(session, files);
        boolean replace = session.getReplaceMediaId() != null;
        TaskType operation = replace ? TaskType.MEDIA_REPLACE : TaskType.MEDIA_UPLOAD;
        List<Long> mediaIds = replace
                ? List.of(session.getReplaceMediaId())
                : insertStagingMedia(session, files, detections);

        String idempotencyKey = "upload:" + session.getSessionId();
        ManagementTaskResponse managementTask = managementTaskService.createTask(
                buildTaskRequest(operation, session), idempotencyKey,
                "{\"session\":\"" + session.getSessionId() + "\"}");
        publishUploadCommands(managementTask, operation, session);

        session.setStatus(UploadSessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        UploadCompleteResponse response = new UploadCompleteResponse();
        response.setTaskId(managementTask.getId());
        response.setTaskType(operation.name());
        response.setStatus(managementTask.getStatus().name());
        response.setItemCount(managementTaskService.getTaskItems(managementTask.getId()).size());
        response.setMediaIds(mediaIds);
        log.info("上传会话 complete: sessionId={}, op={}, taskId={}, mediaIds={}",
                sessionId, operation, managementTask.getId(), mediaIds);
        return response;
    }

    /** 校验全部文件：分片完整 + SHA-256 总校验 + 魔数检测，返回各文件媒体类型检测结果。 */
    private List<MediaTypeDetector.Detection> verifyUploadedFiles(UploadSession session, List<UploadFile> files) {
        List<MediaTypeDetector.Detection> detections = new ArrayList<>(files.size());
        for (UploadFile uploadFile : files) {
            if (!RangeTracker.isFullyReceived(uploadFile.getReceivedRanges(), uploadFile.getSizeBytes())) {
                List<long[]> missingRanges = RangeTracker.missingRanges(
                        uploadFile.getReceivedRanges(), uploadFile.getSizeBytes());
                String miss = missingRanges.stream()
                        .map(byteRange -> byteRange[0] + "-" + byteRange[1])
                        .collect(Collectors.joining(";"));
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + uploadFile.getFileId() + " 未完整接收，缺失区间: " + miss);
            }
            Path staging = storageService.stagingPath(session, uploadFile);
            String actualSha = computeSha256(staging);
            if (!uploadFile.getSha256().equalsIgnoreCase(actualSha)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + uploadFile.getFileId() + " 总校验失败: 声明=" + uploadFile.getSha256() + " 实际=" + actualSha);
            }
            String ext = mediaTypeDetector.validateAndExtractExtension(uploadFile.getStorageName());
            detections.add(mediaTypeDetector.detect(staging, ext));
        }
        return detections;
    }

    /** 为新增上传预建 STAGING media 行（追加到章节末尾 pageNumber），返回媒体 ID 列表。 */
    private List<Long> insertStagingMedia(UploadSession session, List<UploadFile> files,
                                          List<MediaTypeDetector.Detection> detections) {
        List<Long> mediaIds = new ArrayList<>(files.size());
        int nextPage = nextPageNumber(session.getChapterId());
        for (int index = 0; index < files.size(); index++) {
            UploadFile uploadFile = files.get(index);
            MediaTypeDetector.Detection detection = detections.get(index);
            Media media = new Media();
            media.setChapterId(session.getChapterId());
            media.setPageNumber(nextPage + index);
            media.setHqRoot(StorageRootKeys.HQ);
            media.setHqPath(session.getComicId() + "/" + session.getChapterId() + "/" + uploadFile.getStorageName());
            media.setHqStatus(HqStatus.PENDING);
            media.setLqStatus(LqStatus.NOT_GENERATED);
            media.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
            media.setStatus(MediaLifecycleStatus.STAGING);
            media.setMediaType(detection.mediaType());
            media.setHqSize(uploadFile.getSizeBytes());
            media.setVersion(INITIAL_VERSION);
            mediaMapper.insert(media);

            fileMapper.update(null, new LambdaUpdateWrapper<UploadFile>()
                    .eq(UploadFile::getId, uploadFile.getId())
                    .set(UploadFile::getMediaId, media.getId()));
            mediaIds.add(media.getId());
        }
        return mediaIds;
    }

    /** 同事务向 Outbox 逐项发布上传命令事件（提交后由 relay 发布到 MQ）。 */
    private void publishUploadCommands(ManagementTaskResponse managementTask, TaskType operation,
                                       UploadSession session) {
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(managementTask.getId());
        for (ManagementTaskItemResponse item : items) {
            outboxService.enqueue(new ManagementCommandRequestedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    item.getTaskId(), item.getId(), item.getAttempt(),
                    operation.name(), TARGET_TYPE_UPLOAD_SESSION, session.getId()),
                    MANAGEMENT_EXCHANGE, COMMAND_REQUEST_ROUTING_KEY,
                    item.getTaskId(), item.getId(), item.getAttempt());
        }
    }

    private CreateManagementTaskRequest buildTaskRequest(TaskType operation, UploadSession session) {
        CreateManagementTaskRequest request = new CreateManagementTaskRequest();
        request.setTaskType(operation);
        request.setOperation("媒体上传" + (operation == TaskType.MEDIA_REPLACE ? "替换" : ""));
        request.setTargetType(TARGET_TYPE_UPLOAD_SESSION);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(TARGET_TYPE_UPLOAD_SESSION);
        target.setTargetId(session.getId());
        target.setOperationType(operation);
        request.setTargets(List.of(target));
        return request;
    }

    private int nextPageNumber(Long chapterId) {
        return mediaMapper.selectList(
                        new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapterId))
                .stream()
                .map(Media::getPageNumber)
                .filter(pageNumber -> pageNumber != null)
                .max(Comparator.naturalOrder())
                .orElse(0);
    }

    private String computeSha256(Path file) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[SHA256_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    messageDigest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "计算文件 SHA-256 失败: " + ex.getMessage());
        }
    }

    // ======================== 取消/过期 ========================

    @Transactional
    public void cancel(String sessionId) {
        UploadSession session = getBySessionId(sessionId);
        if (session.getStatus() == UploadSessionStatus.CANCELLED) {
            return;
        }
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话已 complete，无法取消");
        }
        storageService.deleteStagingDir(session);
        fileMapper.delete(new LambdaQueryWrapper<UploadFile>()
                .eq(UploadFile::getSessionId, session.getId()));
        session.setStatus(UploadSessionStatus.CANCELLED);
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
                        .eq(UploadSession::getStatus, UploadSessionStatus.ACTIVE)
                        .lt(UploadSession::getExpiresAt, LocalDateTime.now()));
        for (UploadSession session : expired) {
            storageService.deleteStagingDir(session);
            fileMapper.delete(new LambdaQueryWrapper<UploadFile>()
                    .eq(UploadFile::getSessionId, session.getId()));
            session.setStatus(UploadSessionStatus.EXPIRED);
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
