package com.comicatlas.api.upload.application.service.impl;
import com.comicatlas.api.upload.domain.RangeTracker;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import com.comicatlas.api.upload.application.support.MediaTypeDetector;
import com.comicatlas.api.upload.infrastructure.config.UploadProperties;
import com.comicatlas.api.shared.crypto.DigestService;

// 条件更新由上传业务服务维护会话状态机与事务边界，Mapper 执行参数化更新。
// 架构说明：Service 直接构造 LambdaUpdateWrapper 更新上传会话/文件；条件更新应收口到对应 Mapper。
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.api.upload.interfaces.rest.dto.CreateUploadSessionRequest;
import com.comicatlas.api.upload.interfaces.rest.dto.CreateUploadSessionResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadChunkResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadCompleteResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadFileResponse;
import com.comicatlas.api.upload.interfaces.rest.dto.UploadSessionStatusResponse;
import com.comicatlas.api.upload.application.port.in.UploadSessionService;
import com.comicatlas.api.upload.application.port.out.UploadStoragePort;
import com.comicatlas.api.upload.application.port.out.UploadSessionPersistencePort;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 媒体上传/替换会话服务；前端入口为 {@code /manage/upload}，不属于漫画导入主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadSessionServiceImpl implements UploadSessionService {
    // 上传会话契约由 Controller/事件适配器固定，具体实现保持在上传业务包内。

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
    /** 禁止上传媒体的漫画终态集合（复用避免每次构造）。 */
    private static final Set<ComicStatus> NON_UPLOADABLE_STATUSES =
            Set.of(ComicStatus.DELETED, ComicStatus.DELETING, ComicStatus.TRASHED,
                    ComicStatus.PURGING, ComicStatus.RESTORING);

    private final UploadSessionPersistencePort persistencePort;
    private final UploadProperties uploadProperties;
    private final UploadStoragePort storageService;
    private final MediaTypeDetector mediaTypeDetector;
    private final ManagementTaskService managementTaskService;
    private final OutboxService outboxService;
    private final DigestService digestService;
    private final TransactionTemplate transactionTemplate;

    // ======================== 创建 ========================

    @Transactional
    public CreateUploadSessionResponse create(CreateUploadSessionRequest request) {
        Long comicId = request.getComicId();
        Long chapterId = request.getChapterId();
        validateTarget(comicId, chapterId, request.getReplaceMediaId());

        long totalBytes = validateManifest(request.getFiles());
        storageService.ensureEnoughFreeSpace(totalBytes);

        String sessionId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plus(uploadProperties.getSessionTtl());
        Long sessionDatabaseId = persistencePort.insertSession(new UploadSessionPersistencePort.CreateSessionCommand(
                sessionId, comicId, chapterId, request.getReplaceMediaId(), UploadSessionStatus.ACTIVE,
                totalBytes, request.getFiles().size(), expiresAt));
        UploadSessionPersistencePort.SessionSnapshot session = new UploadSessionPersistencePort.SessionSnapshot(
                sessionDatabaseId, sessionId, comicId, chapterId, request.getReplaceMediaId(),
                UploadSessionStatus.ACTIVE, totalBytes, request.getFiles().size(), expiresAt, null);
        storageService.ensureStagingDir(session.sessionId());

        List<UploadFileResponse> fileResponses = insertFiles(session, request.getFiles());

        CreateUploadSessionResponse response = new CreateUploadSessionResponse();
        response.setSessionId(session.sessionId());
        response.setChunkSize(uploadProperties.getChunkSize());
        response.setExpiresAt(session.expiresAt());
        response.setTotalBytes(totalBytes);
        response.setFiles(fileResponses);
        log.info("创建上传会话: sessionId={}, comicId={}, chapterId={}, files={}, bytes={}",
                session.sessionId(), comicId, chapterId, request.getFiles().size(), totalBytes);
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
    private List<UploadFileResponse> insertFiles(UploadSessionPersistencePort.SessionSnapshot session,
                                                 List<CreateUploadSessionRequest.FileManifest> manifest) {
        List<UploadFileResponse> fileResponses = new ArrayList<>(manifest.size());
        for (CreateUploadSessionRequest.FileManifest fileManifest : manifest) {
            String ext = mediaTypeDetector.validateAndExtractExtension(fileManifest.getName());
            String storageName = UUID.randomUUID() + "." + ext;
            persistencePort.insertFile(new UploadSessionPersistencePort.CreateFileCommand(
                    session.id(), fileManifest.getFileId(), fileManifest.getName(), fileManifest.getContentType(),
                    fileManifest.getSize(), fileManifest.getSha256().toLowerCase(Locale.ROOT), storageName, 0L, null));

            UploadFileResponse fileResponse = new UploadFileResponse();
            fileResponse.setFileId(fileManifest.getFileId());
            fileResponse.setStorageName(storageName);
            fileResponse.setReceivedBytes(0);
            fileResponse.setSizeBytes(fileManifest.getSize());
            fileResponse.setComplete(false);
            fileResponses.add(fileResponse);
        }
        return fileResponses;
    }

    private void validateTarget(Long comicId, Long chapterId, Long replaceMediaId) {
        UploadSessionPersistencePort.ComicSnapshot comic = persistencePort.findComic(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在: " + comicId);
        }
        if (NON_UPLOADABLE_STATUSES.contains(comic.status())) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "漫画状态 " + comic.status() + " 不允许上传媒体");
        }
        UploadSessionPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(chapterId);
        if (chapter == null || !chapter.comicId().equals(comicId)) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不属于该漫画: " + chapterId);
        }
        if (replaceMediaId != null) {
            UploadSessionPersistencePort.MediaSnapshot media = persistencePort.findMedia(replaceMediaId);
            if (media == null || !media.chapterId().equals(chapterId)) {
                throw new BusinessException(HttpStatusCodes.NOT_FOUND, "替换目标媒体不存在或不属于该章节: " + replaceMediaId);
            }
            if (media.status() != MediaLifecycleStatus.READY) {
                throw new BusinessException(HttpStatusCodes.CONFLICT, "替换目标媒体状态 " + media.status() + " 不允许替换");
            }
        }
    }

    // ======================== 查询 ========================

    /**
     * 按对外会话 ID 查询上传会话，不存在抛出 404。
     * <p>
     * 内部方法返回应用层会话快照，禁止将持久化模型泄漏到接口层。
     *
     * @param sessionId 对外 opaque 会话 ID
     * @return 上传会话实体
     */
    private UploadSessionPersistencePort.SessionSnapshot getBySessionId(String sessionId) {
        UploadSessionPersistencePort.SessionSnapshot session = persistencePort.findBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        return session;
    }

    private List<UploadSessionPersistencePort.FileSnapshot> filesOf(
            UploadSessionPersistencePort.SessionSnapshot session) {
        return persistencePort.findFiles(session.id());
    }

    public UploadSessionStatusResponse status(String sessionId) {
        UploadSessionPersistencePort.SessionSnapshot session = getBySessionId(sessionId);
        UploadSessionStatusResponse response = new UploadSessionStatusResponse();
        response.setSessionId(session.sessionId());
        response.setStatus(session.status() == null ? null : session.status().name());
        response.setTotalBytes(session.totalBytes());
        response.setTotalFiles(session.totalFiles());
        response.setExpiresAt(session.expiresAt());
        response.setCompletedAt(session.completedAt());
        response.setFiles(filesOf(session).stream().map(this::toFileResponse).toList());
        return response;
    }

    private UploadFileResponse toFileResponse(UploadSessionPersistencePort.FileSnapshot uploadFile) {
        UploadFileResponse fileResponse = new UploadFileResponse();
        fileResponse.setFileId(uploadFile.fileId());
        fileResponse.setStorageName(uploadFile.storageName());
        fileResponse.setReceivedBytes(uploadFile.receivedBytes() != null ? uploadFile.receivedBytes() : 0);
        fileResponse.setSizeBytes(uploadFile.sizeBytes());
        fileResponse.setComplete(RangeTracker.isFullyReceived(uploadFile.receivedRanges(), uploadFile.sizeBytes()));
        fileResponse.setReceivedRanges(uploadFile.receivedRanges() != null ? uploadFile.receivedRanges() : "");
        return fileResponse;
    }

    // ======================== 分片上传 ========================

    public UploadChunkResponse uploadChunk(String sessionId, String fileId,
                                           String contentRange, String chunkSha256,
                                           InputStream input) {
        UploadSessionPersistencePort.SessionSnapshot session = getBySessionId(sessionId);
        UploadSessionPersistencePort.FileSnapshot file = persistencePort.findFile(session.id(), fileId);
        if (file == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "会话中不存在文件: " + fileId);
        }
        long[] byteRange = parseContentRange(contentRange, file.sizeBytes());
        UploadStoragePort.WriteChunkResult writeResult = storageService.writeChunk(
                session.sessionId(), file.id(), file.storageName(), session.status(),
                file.sizeBytes(), file.receivedRanges(), byteRange[0], byteRange[1], byteRange[2],
                chunkSha256, input);
        String merged = writeResult.receivedRanges();

        UploadChunkResponse response = new UploadChunkResponse();
        response.setFileId(file.fileId());
        response.setReceivedBytes(writeResult.receivedBytes());
        response.setComplete(RangeTracker.isFullyReceived(merged, file.sizeBytes()));
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

    public UploadCompleteResponse complete(String sessionId) {
        UploadSessionPersistencePort.SessionSnapshot session = getBySessionId(sessionId);
        if (session.status() != UploadSessionStatus.ACTIVE) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话状态 " + session.status() + " 不允许 complete");
        }
        int frozenRows = persistencePort.freezeForVerification(session.id());
        if (frozenRows != 1) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "上传会话正在被其他操作处理");
        }
        List<UploadSessionPersistencePort.FileSnapshot> files = filesOf(session);
        if (files.isEmpty()) {
            restoreActive(session.id());
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "会话为空，无文件可提交");
        }

        List<MediaTypeDetector.Detection> detections;
        try {
            detections = verifyUploadedFiles(session, files);
        } catch (BusinessException | DataAccessException exception) {
            restoreActive(session.id());
            throw exception;
        }
        try {
            return transactionTemplate.execute(status -> completePersisted(
                    session.id(), sessionId, files, detections));
        } catch (BusinessException | DataAccessException exception) {
            restoreActive(session.id());
            throw exception;
        }
    }

    /** 在文件校验完成后执行短事务落库；此时会话必须仍处于 VERIFYING。 */
    private UploadCompleteResponse completePersisted(Long sessionDatabaseId, String sessionId,
                                                     List<UploadSessionPersistencePort.FileSnapshot> files,
                                                     List<MediaTypeDetector.Detection> detections) {
        UploadSessionPersistencePort.SessionSnapshot session = persistencePort.findById(sessionDatabaseId);
        if (session == null || session.status() != UploadSessionStatus.VERIFYING) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "上传会话状态已变化，请重新提交");
        }
        boolean replace = session.replaceMediaId() != null;
        TaskType operation = replace ? TaskType.MEDIA_REPLACE : TaskType.MEDIA_UPLOAD;
        List<Long> mediaIds = replace
                ? List.of(session.replaceMediaId())
                : insertStagingMedia(session, files, detections);

        String idempotencyKey = "upload:" + session.sessionId();
        ManagementTaskResponse managementTask = managementTaskService.createTask(
                buildTaskRequest(operation, session), idempotencyKey,
                "{\"session\":\"" + session.sessionId() + "\"}");
        publishUploadCommands(managementTask, operation, session);

        persistencePort.updateSession(new UploadSessionPersistencePort.UpdateSessionCommand(
                session.id(), UploadSessionStatus.COMPLETED, LocalDateTime.now()));

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

    private void restoreActive(Long sessionDatabaseId) {
        persistencePort.restoreActive(sessionDatabaseId);
    }

    /** 校验全部文件：分片完整 + SHA-256 总校验 + 魔数检测，返回各文件媒体类型检测结果。 */
    private List<MediaTypeDetector.Detection> verifyUploadedFiles(
            UploadSessionPersistencePort.SessionSnapshot session,
            List<UploadSessionPersistencePort.FileSnapshot> files) {
        List<MediaTypeDetector.Detection> detections = new ArrayList<>(files.size());
        for (UploadSessionPersistencePort.FileSnapshot uploadFile : files) {
            if (!RangeTracker.isFullyReceived(uploadFile.receivedRanges(), uploadFile.sizeBytes())) {
                List<long[]> missingRanges = RangeTracker.missingRanges(
                        uploadFile.receivedRanges(), uploadFile.sizeBytes());
                String miss = missingRanges.stream()
                        .map(byteRange -> byteRange[0] + "-" + byteRange[1])
                        .collect(Collectors.joining(";"));
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + uploadFile.fileId() + " 未完整接收，缺失区间: " + miss);
            }
            Path staging = storageService.stagingPath(session.sessionId(), uploadFile.storageName());
            String actualSha = computeSha256(staging);
            if (!uploadFile.sha256().equalsIgnoreCase(actualSha)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "文件 " + uploadFile.fileId() + " 总校验失败: 声明=" + uploadFile.sha256() + " 实际=" + actualSha);
            }
            String ext = mediaTypeDetector.validateAndExtractExtension(uploadFile.storageName());
            detections.add(mediaTypeDetector.detect(staging, ext));
        }
        return detections;
    }

    /** 为新增上传预建 STAGING media 行（追加到章节末尾 pageNumber），返回媒体 ID 列表。 */
    private List<Long> insertStagingMedia(UploadSessionPersistencePort.SessionSnapshot session,
                                          List<UploadSessionPersistencePort.FileSnapshot> files,
                                          List<MediaTypeDetector.Detection> detections) {
        List<Long> mediaIds = new ArrayList<>(files.size());
        int nextPage = nextPageNumber(session.chapterId());
        for (int index = 0; index < files.size(); index++) {
            UploadSessionPersistencePort.FileSnapshot uploadFile = files.get(index);
            MediaTypeDetector.Detection detection = detections.get(index);
            Long mediaId = persistencePort.insertMedia(new UploadSessionPersistencePort.MediaCreateCommand(
                    session.chapterId(), nextPage + index, StorageRootKeys.HQ,
                    session.comicId() + "/" + session.chapterId() + "/" + uploadFile.storageName(),
                    detection.mediaType(), uploadFile.sizeBytes()));

            persistencePort.bindMedia(uploadFile.id(), mediaId);
            mediaIds.add(mediaId);
        }
        return mediaIds;
    }

    /** 同事务向 Outbox 逐项发布上传命令事件（提交后由 relay 发布到 MQ）。 */
    private void publishUploadCommands(ManagementTaskResponse managementTask, TaskType operation,
                                       UploadSessionPersistencePort.SessionSnapshot session) {
        List<ManagementTaskItemResponse> items = managementTaskService.getTaskItems(managementTask.getId());
        for (ManagementTaskItemResponse item : items) {
            outboxService.enqueue(new ManagementCommandRequestedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    item.getTaskId(), item.getId(), item.getAttempt(),
                    operation.name(), TARGET_TYPE_UPLOAD_SESSION, session.id()),
                    MANAGEMENT_EXCHANGE, COMMAND_REQUEST_ROUTING_KEY,
                    item.getTaskId(), item.getId(), item.getAttempt());
        }
    }

    private CreateManagementTaskRequest buildTaskRequest(
            TaskType operation, UploadSessionPersistencePort.SessionSnapshot session) {
        CreateManagementTaskRequest request = new CreateManagementTaskRequest();
        request.setTaskType(operation);
        request.setOperation("媒体上传" + (operation == TaskType.MEDIA_REPLACE ? "替换" : ""));
        request.setTargetType(TARGET_TYPE_UPLOAD_SESSION);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(TARGET_TYPE_UPLOAD_SESSION);
        target.setTargetId(session.id());
        target.setOperationType(operation);
        request.setTargets(List.of(target));
        return request;
    }

    private int nextPageNumber(Long chapterId) {
        return persistencePort.findMediaByChapter(chapterId)
                .stream()
                .map(UploadSessionPersistencePort.MediaSnapshot::pageNumber)
                .filter(pageNumber -> pageNumber != null)
                .max(Comparator.naturalOrder())
                .orElse(0);
    }

    private String computeSha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return digestService.sha256(input);
        } catch (IOException ex) {
            throw new BusinessException("计算文件 SHA-256 失败", ex);
        }
    }

    // ======================== 取消/过期 ========================

    @Transactional
    public void cancel(String sessionId) {
        UploadSessionPersistencePort.SessionSnapshot session = getBySessionId(sessionId);
        if (session.status() == UploadSessionStatus.CANCELLED) {
            return;
        }
        if (session.status() == UploadSessionStatus.COMPLETED) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "会话已 complete，无法取消");
        }
        storageService.deleteStagingDir(session.sessionId());
        persistencePort.deleteFiles(session.id());
        persistencePort.updateSession(new UploadSessionPersistencePort.UpdateSessionCommand(
                session.id(), UploadSessionStatus.CANCELLED, session.completedAt()));
        log.info("取消上传会话: sessionId={}", sessionId);
    }

    /**
     * 过期清理：24h 未完成的 ACTIVE 会话标记 EXPIRED 并删除 STAGING 文件。
     */
    @Transactional
    public int expireExpiredSessions() {
        List<UploadSessionPersistencePort.SessionSnapshot> expired =
                persistencePort.findExpiredActive(LocalDateTime.now());
        for (UploadSessionPersistencePort.SessionSnapshot session : expired) {
            storageService.deleteStagingDir(session.sessionId());
            persistencePort.deleteFiles(session.id());
            persistencePort.updateSession(new UploadSessionPersistencePort.UpdateSessionCommand(
                    session.id(), UploadSessionStatus.EXPIRED, session.completedAt()));
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
        UploadSessionPersistencePort.SessionSnapshot session = persistencePort.findById(sessionId);
        if (session == null) {
            return;
        }
            storageService.deleteStagingDir(session.sessionId());
        persistencePort.deleteFiles(sessionId);
        persistencePort.deleteSession(sessionId);
        log.info("会话处理完成，清理 STAGING 与会话行: sessionId={}", session.sessionId());
    }
}
