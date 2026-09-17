package com.comicatlas.api.importer.application.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.importer.interfaces.rest.dto.BatchImportRequest;
import com.comicatlas.api.importer.interfaces.rest.dto.BatchImportResultVO;
import com.comicatlas.api.importer.interfaces.rest.dto.FailedItem;
import com.comicatlas.api.importer.interfaces.rest.dto.ImportRequest;
import com.comicatlas.api.importer.interfaces.rest.dto.ImportStatusVO;
import com.comicatlas.api.importer.interfaces.rest.dto.ImportTaskVO;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.application.port.out.ImportCommandPersistencePort;
import com.comicatlas.api.importer.application.service.ImportRetryCoordinator;
import com.comicatlas.api.importer.application.port.in.ImportService;
import com.comicatlas.api.task.interfaces.rest.dto.CreateManagementTaskRequest;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTask;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.outbox.application.port.in.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.CancelTaskEvent;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.shared.crypto.DigestService;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private final DigestService digestService;

    /** Redis 导入取消标记 key 前缀（与 Worker CancelHandler.KEY_PREFIX 契约一致）。 */
    private static final String IMPORT_CANCEL_KEY_PREFIX = "import:cancel:";
    /** Redis EHENTAI 导入去重 key 前缀。 */
    private static final String EHENTAI_DEDUP_KEY_PREFIX = "import:dedup:E_HENTAI:";
    /** Redis 去重/取消标记保留时长。 */
    private static final Duration REDIS_MARK_TTL = Duration.ofDays(7);
    /** 管理任务目标类型：漫画。 */
    private static final String TARGET_TYPE_COMIC = "COMIC";
    /** 管理任务项结果引用类型：导入任务。 */
    private static final String RESULT_REF_TYPE_IMPORT_TASK = "IMPORT_TASK";
    /** 列表分页默认页码。 */
    private static final int DEFAULT_PAGE_NUMBER = 1;
    /** 列表分页默认页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** EHENTAI 画廊 URL 校验正则，与 Worker 的 worker.ehentai.gallery-url-pattern 同源可配 */
    @Value("${comic.import.ehentai-url-pattern:e-hentai\\.org/g/(\\d+)/([a-f0-9]+)}")
    private String ehentaiUrlPattern;

    private Pattern ehentaiPattern;

    @PostConstruct
    private void initEhentaiPattern() {
        ehentaiPattern = Pattern.compile(ehentaiUrlPattern);
    }

    private final ImportCommandPersistencePort persistencePort;
    private final OutboxService outboxService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ManagementTaskService managementTaskService;
    private final ApiStorageProperties storageProperties;
    private final ImportRetryCoordinator importRetryCoordinator;

    @Override
    @Transactional
    public ImportTaskVO createImportTask(ImportRequest request, String idempotencyKey) {
        // 幂等：同 Idempotency-Key 同 payload 直接返回已有任务
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            ManagementTask existing = managementTaskService.findByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                String expectedHash = digestService.sha256(payload(request));
                if (!expectedHash.equals(existing.getIdempotencyPayloadHash())) {
                    throw new ConflictException("幂等键 " + idempotencyKey + " 已存在但 payload 不匹配");
                }
                ImportTask existingImport = persistencePort.findByManagementTaskId(existing.getId());
                if (existingImport != null) {
                    log.info("导入幂等命中 idempotencyKey={}, 返回已有任务 {}", idempotencyKey, existingImport.getId());
                    return toVO(existingImport);
                }
            }
        }

        String sourceType = request.getSourceType() != null ? request.getSourceType() : SourceType.EHENTAI.name();
        String sourcePath = request.getSourcePath();
        String sourceRef = request.getSourceRef();

        // 1. 预创建 comic 行
        ImportCommandPersistencePort.ComicCreateCommand comicCommand =
                new ImportCommandPersistencePort.ComicCreateCommand(
                        toSourceType(sourceType), ComicStatus.IMPORTING, "导入中...",
                        null, null, sourceRef);
        ImportCommandPersistencePort.ComicSnapshot comic;

        switch (sourceType) {
            case "EHENTAI" -> {
                if (sourceRef == null || !ehentaiPattern.matcher(sourceRef).find()) {
                    throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的 URL 格式");
                }
                Matcher matcher = ehentaiPattern.matcher(sourceRef);
                matcher.find();
                String galleryId = matcher.group(1);
                String galleryToken = matcher.group(2);
                comicCommand = new ImportCommandPersistencePort.ComicCreateCommand(
                        toSourceType(sourceType), ComicStatus.IMPORTING, "导入中...",
                        galleryId, galleryToken, sourceRef);
                // Redis 去重
                String dedupKey = EHENTAI_DEDUP_KEY_PREFIX + galleryId;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已存在或正在导入中");
                }
                // DB 去重
                ImportCommandPersistencePort.ComicSnapshot existingComic =
                        persistencePort.findComicBySourceGallery(SourceType.EHENTAI.name(), galleryId);
                if (existingComic != null) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT,
                            "该漫画已导入 - 漫画ID: " + existingComic.id());
                }
                try {
                    comic = persistencePort.insertComic(comicCommand);
                } catch (DuplicateKeyException ex) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已存在（并发导入）");
                }
                redisTemplate.opsForValue().set(dedupKey, "1", REDIS_MARK_TTL);
            }
            case "ZIP", "CBZ", "DIRECTORY" -> {
                String path = sourcePath != null ? sourcePath : sourceRef;
                if (path == null || path.isBlank()) {
                    throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请提供 sourcePath");
                }
                String name = Path.of(path).getFileName().toString();
                name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                comicCommand = new ImportCommandPersistencePort.ComicCreateCommand(
                        toSourceType(sourceType), ComicStatus.IMPORTING, name,
                        null, null, path);
                comic = persistencePort.insertComic(comicCommand);
            }
            default -> throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的 sourceType: " + sourceType);
        }

        // 2. 创建 import_task
        ImportTask task = new ImportTask();
        task.setComicId(comic.id());
        task.setSourceRef(sourceRef);
        task.setSourceType(toSourceType(sourceType));
        task.setSourcePath(sourcePath);
        task.setStatus(ImportTaskStatus.PENDING);
        persistencePort.insertImportTask(task);

        // 3. 同事务创建 management task（预创建 comic 与统一任务绑定）
        ManagementTaskResponse managementTaskResponse =
                createManagementTaskForImport(comic.id(), idempotencyKey, payload(request));

        // 4. 回填 import_task.management_task_id
        task.setManagementTaskId(managementTaskResponse.getId());
        persistencePort.updateImportTask(task);

        // 5. 将事件写入 Outbox（与 DB 同事务），由 relay 异步发布到 MQ
        // EHENTAI 只有 sourceRef（gallery URL），事件契约仍使用 sourcePath 字段承载 Worker 的入口参数。
        String eventSourcePath = sourcePath != null && !sourcePath.isBlank() ? sourcePath : sourceRef;
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), task.getId(), comic.id(), sourceType, eventSourcePath);
        outboxService.enqueue(event, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导入任务创建: taskId={}, comicId={}, managementTaskId={}, sourceType={}",
                task.getId(), comic.id(), task.getManagementTaskId(), sourceType);
        return toVO(task);
    }

    @Override
    public IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status, String batchId) {
        ImportTaskStatus statusEnum = status != null ? parseImportStatus(status) : null;
        Page<ImportTask> pageRequest = new Page<>(
                page != null ? page : DEFAULT_PAGE_NUMBER, size != null ? size : DEFAULT_PAGE_SIZE);
        return persistencePort.findPage(pageRequest, statusEnum, batchId).convert(this::toVO);
    }

    @Override
    public BatchImportResultVO createBatchImportTasks(BatchImportRequest request) {
        List<String> sourcePaths = request.getSourcePaths();
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请至少选择一个目录");
        }

        String sourceType = request.getSourceType() != null && !request.getSourceType().isBlank()
                ? request.getSourceType() : SourceType.DIRECTORY.name();
        String batchId = UUID.randomUUID().toString();

        List<ImportTaskVO> succeeded = new ArrayList<>();
        List<FailedItem> failed = new ArrayList<>();

        for (String path : sourcePaths) {
            try {
                long[] createdIds = transactionTemplate.execute(status -> {
                    String name = Path.of(path).getFileName().toString();
                    name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;

                    ImportCommandPersistencePort.ComicSnapshot comic = persistencePort.insertComic(
                            new ImportCommandPersistencePort.ComicCreateCommand(
                                    toSourceType(sourceType), ComicStatus.IMPORTING, name,
                                    null, null, path));

                    ImportTask task = new ImportTask();
                    task.setComicId(comic.id());
                    task.setSourceType(toSourceType(sourceType));
                    task.setSourcePath(path);
                    task.setBatchId(batchId);
                    task.setStatus(ImportTaskStatus.PENDING);
                    persistencePort.insertImportTask(task);

                    // 同步建立统一任务并回填 management_task_id
                    ManagementTaskResponse managementTaskResponse = createManagementTaskForImport(comic.id(), null, null);
                    task.setManagementTaskId(managementTaskResponse.getId());
                    persistencePort.updateImportTask(task);

                    // 写入 Outbox（同事务）
                    ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                            UUID.randomUUID(), Instant.now(), task.getId(), comic.id(), sourceType, path);
                    outboxService.enqueue(event, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

                    return new long[]{task.getId(), comic.id()};
                });

                long taskId = createdIds[0];

                ImportTask task = persistencePort.findImportTask(taskId);
                succeeded.add(toVO(task));

            } catch (BusinessException | DataAccessException ex) {
                log.error("批量导入单任务失败: path={}", path, ex);
                FailedItem item = new FailedItem();
                item.setSourcePath(path);
                item.setErrorMessage(ex.getMessage());
                failed.add(item);
            }
        }

        log.info("批量导入完成: batchId={}, total={}, succeeded={}, failed={}",
                batchId, sourcePaths.size(), succeeded.size(), failed.size());

        BatchImportResultVO result = new BatchImportResultVO();
        result.setBatchId(batchId);
        result.setTotal(sourcePaths.size());
        result.setSucceeded(succeeded);
        result.setFailed(failed);
        return result;
    }

    @Override
    public ImportTaskVO getTaskDetail(Long id) {
        ImportTask task = persistencePort.findImportTask(id);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        return toVO(task);
    }

    @Override
    public ImportStatusVO getTaskStatus(Long id) {
        ImportTask task = persistencePort.findImportTask(id);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        ImportStatusVO statusView = new ImportStatusVO();
        statusView.setTaskId(task.getId());
        statusView.setStatus(statusName(task.getStatus()));
        statusView.setProgress(task.getProgress());
        return statusView;
    }

    @Override
    @Transactional
    public void cancelTask(Long id) {
        ImportTask task = persistencePort.findImportTask(id);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "终态任务不可取消");
        }
        task.setStatus(ImportTaskStatus.CANCELLED);
        persistencePort.updateImportTask(task);

        Long taskId = task.getId();
        Long comicId = task.getComicId();

        // 同步统一任务为 CANCELLED 真正终态（即使 item 已 RUNNING）
        if (task.getManagementTaskId() != null) {
            ManagementTaskItem managementItem = managementTaskService.findActiveItem(
                    TARGET_TYPE_COMIC, comicId, TaskType.IMPORT);
            if (managementItem != null) {
                managementTaskService.updateItemStatus(managementItem.getId(), ManagementTaskStatus.CANCELLED,
                        "导入已取消", RESULT_REF_TYPE_IMPORT_TASK, taskId);
            }
        }

        // 写入 Outbox（同事务），由 relay 发布到 MQ
        CancelTaskEvent cancelEvent = new CancelTaskEvent(UUID.randomUUID(), Instant.now(), taskId, comicId);
        outboxService.enqueue(cancelEvent, MqExchanges.TASK, MqRoutingKeys.CANCEL_REQUESTED);

        // Redis 取消标记（非关键，无需事务保障）
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            redisTemplate.opsForValue().set(
                                    IMPORT_CANCEL_KEY_PREFIX + taskId, "1", REDIS_MARK_TTL);
                        } catch (RedisSystemException ex) {
                            log.warn("取消标记写入失败（非关键）: taskId={}", taskId, ex);
                        }
                    }
                });
    }

    @Override
    @Transactional
    public void retryTask(Long id) {
        ImportTask task = persistencePort.findImportTask(id);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        }
        ImportTaskStatus taskStatus = task.getStatus();
        if (taskStatus != ImportTaskStatus.FAILED && taskStatus != ImportTaskStatus.CANCELLED) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "仅 FAILED/CANCELLED 状态可重试");
        }

        // 统一重试编排：清理旧章节 → import_task 重置 PENDING → comic IMPORTING → 重发 ImportTaskCreatedEvent
        importRetryCoordinator.retry(task);

        // 同步统一任务：终态统一任务重置回 QUEUED（attempt 递增，失败/取消 item 重新入队）
        // IMPORT 类型 item 由 ImportRetryCoordinator 幂等守卫保证不重复入队（此时 import_task 已非终态）
        if (task.getManagementTaskId() != null) {
            try {
                managementTaskService.retryTask(task.getManagementTaskId());
            } catch (BusinessException ex) {
                log.warn("统一任务重试跳过（非终态）: managementTaskId={}",
                        task.getManagementTaskId(), ex);
            }
        }
    }

    /** 幂等 payload：导入请求的确定性表示 */
    private static String payload(ImportRequest request) {
        return request.toString();
    }

    /**
     * 同事务创建统一导入任务并返回其响应。
     */
    private ManagementTaskResponse createManagementTaskForImport(Long comicId, String idempotencyKey, String payload) {
        CreateManagementTaskRequest managementTaskRequest = new CreateManagementTaskRequest();
        managementTaskRequest.setTaskType(TaskType.IMPORT);
        managementTaskRequest.setOperation("导入漫画");
        managementTaskRequest.setTargetType(TARGET_TYPE_COMIC);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(TARGET_TYPE_COMIC);
        target.setTargetId(comicId);
        target.setOperationType(TaskType.IMPORT);
        managementTaskRequest.setTargets(List.of(target));
        return managementTaskService.createTask(managementTaskRequest, idempotencyKey, payload);
    }

    private static SourceType toSourceType(String sourceType) {
        if (sourceType == null) {
            return null;
        }
        try {
            return SourceType.valueOf(sourceType);
        } catch (IllegalArgumentException ex) {
            log.warn("未知 sourceType={}，映射为 null（调用方将按 DIRECTORY 兜底）", sourceType);
            return null;
        }
    }

    private static String resolveSourceType(ImportTask task) {
        return task.getSourceType() != null ? task.getSourceType().name() : SourceType.DIRECTORY.name();
    }

    private static String statusName(ImportTaskStatus status) {
        return status == null ? null : status.name();
    }

    private static ImportTaskStatus parseImportStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ImportTaskStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的任务状态: " + status);
        }
    }

    private ImportTaskVO toVO(ImportTask task) {
        ImportTaskVO taskView = new ImportTaskVO();
        taskView.setId(task.getId());
        taskView.setComicId(task.getComicId());
        taskView.setSourceRef(task.getSourceRef());
        taskView.setSourceType(resolveSourceType(task));
        taskView.setSourcePath(task.getSourcePath());
        taskView.setBatchId(task.getBatchId());
        taskView.setStatus(statusName(task.getStatus()));
        taskView.setProgress(task.getProgress());
        taskView.setTotalPages(task.getTotalPages());
        taskView.setDownloadedPages(task.getDownloadedPages());
        taskView.setDownloadMethod(task.getDownloadMethod());
        taskView.setDownloadSpeed(task.getDownloadSpeed());
        taskView.setEtaSeconds(task.getEtaSeconds());
        taskView.setErrorMessage(task.getErrorMessage());
        taskView.setRetryCount(task.getRetryCount());
        taskView.setDurationMs(task.getDurationMs());
        taskView.setStartTime(task.getStartTime());
        taskView.setEndTime(task.getEndTime());
        taskView.setCreatedAt(task.getCreatedAt());
        return taskView;
    }
}
