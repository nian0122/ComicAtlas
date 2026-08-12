package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.contract.common.exception.ConflictException;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportService;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.contract.common.enums.ManagementTaskStatus;
import com.comicatlas.contract.common.enums.TaskType;
import com.comicatlas.common.event.CancelTaskEvent;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.comicatlas.api.importer.dto.BatchImportRequest;
import com.comicatlas.api.importer.dto.BatchImportResultVO;
import com.comicatlas.api.importer.dto.ImportRequest;
import com.comicatlas.api.importer.dto.ImportStatusVO;
import com.comicatlas.api.importer.dto.ImportTaskVO;
import com.comicatlas.api.importer.dto.FailedItem;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    /** EHENTAI 画廊 URL 校验正则，与 Worker 的 worker.ehentai.gallery-url-pattern 同源可配 */
    @Value("${comic.import.ehentai-url-pattern:e-hentai\\.org/g/(\\d+)/([a-f0-9]+)}")
    private String ehentaiUrlPattern;

    private Pattern ehentaiPattern;

    @PostConstruct
    void initEhentaiPattern() {
        ehentaiPattern = Pattern.compile(ehentaiUrlPattern);
    }

    private final ImportTaskMapper taskMapper;
    private final ComicMapper comicMapper;
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
                String expectedHash = sha256(payload(request));
                if (!expectedHash.equals(existing.getIdempotencyPayloadHash())) {
                    throw new ConflictException("幂等键 " + idempotencyKey + " 已存在但 payload 不匹配");
                }
                ImportTask existingImport = taskMapper.selectOne(new LambdaQueryWrapper<ImportTask>()
                        .eq(ImportTask::getManagementTaskId, existing.getId()));
                if (existingImport != null) {
                    log.info("导入幂等命中 idempotencyKey={}, 返回已有任务 {}", idempotencyKey, existingImport.getId());
                    return toVO(existingImport);
                }
            }
        }

        String sourceType = request.getSourceType() != null ? request.getSourceType() : "EHENTAI";
        String sourcePath = request.getSourcePath();
        String sourceRef = request.getSourceRef();

        // 1. 预创建 comic 行
        Comic comic = new Comic();
        comic.setSourceType(toSourceType(sourceType));
        comic.setStatus(ComicStatus.IMPORTING);
        comic.setTitle("导入中...");

        switch (sourceType) {
            case "EHENTAI" -> {
                if (sourceRef == null || !ehentaiPattern.matcher(sourceRef).find()) {
                    throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的 URL 格式");
                }
                Matcher matcher = ehentaiPattern.matcher(sourceRef);
                matcher.find();
                String gid = matcher.group(1);
                String token = matcher.group(2);
                comic.setSourceGalleryId(gid);
                comic.setSourceGalleryToken(token);
                comic.setSourceRef(sourceRef);
                // Redis 去重
                String dedupKey = "import:dedup:E_HENTAI:" + gid;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已存在或正在导入中");
                }
                // DB 去重
                var existing = comicMapper.selectOne(new LambdaQueryWrapper<Comic>()
                    .eq(Comic::getSourceType, SourceType.EHENTAI)
                    .eq(Comic::getSourceGalleryId, gid));
                if (existing != null) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已导入 - 漫画ID: " + existing.getId());
                }
                try {
                    comicMapper.insert(comic);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画已存在（并发导入）");
                }
                redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofDays(7));
            }
            case "ZIP", "DIRECTORY" -> {
                String path = sourcePath != null ? sourcePath : sourceRef;
                if (path == null || path.isBlank()) { throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请提供 sourcePath"); }
                String name = Path.of(path).getFileName().toString();
                name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                comic.setTitle(name);
                comic.setSourceRef(path);
                comicMapper.insert(comic);
            }
            default -> throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的 sourceType: " + sourceType);
        }

        // 2. 创建 import_task
        ImportTask task = new ImportTask();
        task.setComicId(comic.getId());
        task.setSourceRef(sourceRef);
        task.setSourceType(toSourceType(sourceType));
        task.setSourcePath(sourcePath);
        task.setStatus(ImportTaskStatus.PENDING);
        taskMapper.insert(task);

        // 3. 同事务创建 management task（预创建 comic 与统一任务绑定）
        ManagementTaskResponse mgmtResp = createManagementTaskForImport(comic.getId(), idempotencyKey, payload(request));

        // 4. 回填 import_task.management_task_id
        task.setManagementTaskId(mgmtResp.getId());
        taskMapper.updateById(task);

        // 5. 将事件写入 Outbox（与 DB 同事务），由 relay 异步发布到 MQ
        var event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), task.getId(), comic.getId(), sourceType, sourcePath);
        outboxService.enqueue(event, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导入任务创建: taskId={}, comicId={}, managementTaskId={}, sourceType={}",
                task.getId(), comic.getId(), task.getManagementTaskId(), sourceType);
        return toVO(task);
    }

    @Override
    public IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status, String batchId) {
        ImportTaskStatus statusEnum = status != null ? parseImportStatus(status) : null;
        var wrapper = new LambdaQueryWrapper<ImportTask>()
            .eq(statusEnum != null, ImportTask::getStatus, statusEnum)
            .eq(batchId != null, ImportTask::getBatchId, batchId)
            .orderByDesc(ImportTask::getCreatedAt);
        var pageRequest = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ImportTask>(page != null ? page : 1, size != null ? size : 20);
        return taskMapper.selectPage(pageRequest, wrapper).convert(this::toVO);
    }

    @Override
    public BatchImportResultVO createBatchImportTasks(BatchImportRequest request) {
        List<String> sourcePaths = request.getSourcePaths();
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请至少选择一个目录");
        }

        String sourceType = request.getSourceType() != null && !request.getSourceType().isBlank()
            ? request.getSourceType() : "DIRECTORY";
        String batchId = UUID.randomUUID().toString();

        List<ImportTaskVO> succeeded = new ArrayList<>();
        List<FailedItem> failed = new ArrayList<>();

        for (String path : sourcePaths) {
            try {
                long[] ids = transactionTemplate.execute(status -> {
                    String name = Path.of(path).getFileName().toString();
                    name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;

                    Comic comic = new Comic();
                    comic.setSourceType(toSourceType(sourceType));
                    comic.setStatus(ComicStatus.IMPORTING);
                    comic.setTitle(name);
                    comic.setSourceRef(path);
                    comicMapper.insert(comic);

                    ImportTask task = new ImportTask();
                    task.setComicId(comic.getId());
                    task.setSourceType(toSourceType(sourceType));
                    task.setSourcePath(path);
                    task.setBatchId(batchId);
                    task.setStatus(ImportTaskStatus.PENDING);
                    taskMapper.insert(task);

                    // 同步建立统一任务并回填 management_task_id
                    ManagementTaskResponse mgmtResp = createManagementTaskForImport(comic.getId(), null, null);
                    task.setManagementTaskId(mgmtResp.getId());
                    taskMapper.updateById(task);

                    // 写入 Outbox（同事务）
                    var evt = new ImportTaskCreatedEvent(
                            UUID.randomUUID(), Instant.now(), task.getId(), comic.getId(), sourceType, path);
                    outboxService.enqueue(evt, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

                    return new long[]{task.getId(), comic.getId()};
                });

                long taskId = ids[0];

                ImportTask task = taskMapper.selectById(taskId);
                succeeded.add(toVO(task));

            } catch (Exception e) {
                log.error("批量导入单任务失败: path={}, error={}", path, e.getMessage());
                FailedItem item = new FailedItem();
                item.setSourcePath(path);
                item.setErrorMessage(e.getMessage());
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
        ImportTask task = taskMapper.selectById(id);
        if (task == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在"); }
        return toVO(task);
    }

    @Override
    public ImportStatusVO getTaskStatus(Long id) {
        ImportTask task = taskMapper.selectById(id);
        if (task == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在"); }
        ImportStatusVO vo = new ImportStatusVO();
        vo.setTaskId(task.getId());
        vo.setStatus(statusName(task.getStatus()));
        vo.setProgress(task.getProgress());
        return vo;
    }

    @Override
    @Transactional
    public void cancelTask(Long id) {
        ImportTask task = taskMapper.selectById(id);
        if (task == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在"); }
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "终态任务不可取消");
        }
        task.setStatus(ImportTaskStatus.CANCELLED);
        taskMapper.updateById(task);

        Long taskId = task.getId();
        Long comicId = task.getComicId();

        // 同步统一任务为 CANCELLED 真正终态（即使 item 已 RUNNING）
        if (task.getManagementTaskId() != null) {
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem("COMIC", comicId, TaskType.IMPORT);
            if (mgmtItem != null) {
                managementTaskService.updateItemStatus(mgmtItem.getId(), ManagementTaskStatus.CANCELLED,
                        "导入已取消", "IMPORT_TASK", taskId);
            }
        }

        // 写入 Outbox（同事务），由 relay 发布到 MQ
        var cancelEvent = new CancelTaskEvent(UUID.randomUUID(), Instant.now(), taskId, comicId);
        outboxService.enqueue(cancelEvent, MqExchanges.TASK, MqRoutingKeys.CANCEL_REQUESTED);

        // Redis 取消标记（非关键，无需事务保障）
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            redisTemplate.opsForValue().set(
                                    "import:cancel:" + taskId, "1", Duration.ofDays(7));
                        } catch (Exception e) {
                            log.warn("取消标记写入失败（非关键）: taskId={}, error={}", taskId, e.getMessage());
                        }
                    }
                });
    }

    @Override
    @Transactional
    public void retryTask(Long id) {
        ImportTask task = taskMapper.selectById(id);
        if (task == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在"); }
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
            } catch (BusinessException e) {
                log.warn("统一任务重试跳过（非终态）: managementTaskId={}, error={}",
                        task.getManagementTaskId(), e.getMessage());
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
        CreateManagementTaskRequest mgmtReq = new CreateManagementTaskRequest();
        mgmtReq.setTaskType(com.comicatlas.contract.common.enums.TaskType.IMPORT);
        mgmtReq.setOperation("导入漫画");
        mgmtReq.setTargetType("COMIC");
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("COMIC");
        target.setTargetId(comicId);
        target.setOperationType(com.comicatlas.contract.common.enums.TaskType.IMPORT);
        mgmtReq.setTargets(List.of(target));
        return managementTaskService.createTask(mgmtReq, idempotencyKey, payload);
    }

    private static String sha256(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static SourceType toSourceType(String sourceType) {
        if (sourceType == null) { return null; }
        try {
            return SourceType.valueOf(sourceType);
        } catch (IllegalArgumentException e) {
            log.warn("未知 sourceType={}，映射为 null（调用方将按 DIRECTORY 兜底）", sourceType);
            return null;
        }
    }

    private static String resolveSourceType(ImportTask task) {
        return task.getSourceType() != null ? task.getSourceType().name() : "DIRECTORY";
    }

    private static String statusName(ImportTaskStatus status) {
        return status == null ? null : status.name();
    }

    private static ImportTaskStatus parseImportStatus(String status) {
        if (status == null) { return null; }
        try {
            return ImportTaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的任务状态: " + status);
        }
    }

    private ImportTaskVO toVO(ImportTask task) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setId(task.getId());
        vo.setComicId(task.getComicId());
        vo.setSourceRef(task.getSourceRef());
        vo.setSourceType(resolveSourceType(task));
        vo.setSourcePath(task.getSourcePath());
        vo.setBatchId(task.getBatchId());
        vo.setStatus(statusName(task.getStatus()));
        vo.setProgress(task.getProgress());
        vo.setTotalPages(task.getTotalPages());
        vo.setDownloadedPages(task.getDownloadedPages());
        vo.setDownloadMethod(task.getDownloadMethod());
        vo.setDownloadSpeed(task.getDownloadSpeed());
        vo.setEtaSeconds(task.getEtaSeconds());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setRetryCount(task.getRetryCount());
        vo.setDurationMs(task.getDurationMs());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setCreatedAt(task.getCreatedAt());
        return vo;
    }
}
