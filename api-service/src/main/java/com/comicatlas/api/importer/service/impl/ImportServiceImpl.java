package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.SourceType;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.importer.dto.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final OutboxService outboxService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ManagementTaskService managementTaskService;
    private final ApiStorageProperties storageProperties;

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
            case "ZIP", "REGISTER", "DIRECTORY" -> {
                String path = sourcePath != null ? sourcePath : sourceRef;
                if (path == null || path.isBlank()) throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请提供 sourcePath");
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
        task.setSourceType(sourceType);
        task.setSourcePath(sourcePath);
        task.setStatus("PENDING");
        taskMapper.insert(task);

        // 3. 同事务创建 management task（预创建 comic 与统一任务绑定）
        ManagementTaskResponse mgmtResp = createManagementTaskForImport(comic.getId(), idempotencyKey, payload(request));

        // 4. 回填 import_task.management_task_id
        task.setManagementTaskId(mgmtResp.getId());
        taskMapper.updateById(task);

        // 5. 将事件写入 Outbox（与 DB 同事务），由 relay 异步发布到 MQ
        var event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), task.getId(), comic.getId(), sourceType, sourcePath);
        outboxService.enqueue(event, "comic.import", "task.created");

        log.info("导入任务创建: taskId={}, comicId={}, managementTaskId={}, sourceType={}",
                task.getId(), comic.getId(), task.getManagementTaskId(), sourceType);
        return toVO(task);
    }

    @Override
    public IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status, String batchId) {
        var wrapper = new LambdaQueryWrapper<ImportTask>()
            .eq(status != null, ImportTask::getStatus, status)
            .eq(batchId != null, ImportTask::getBatchId, batchId)
            .orderByDesc(ImportTask::getCreatedAt);
        var p = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ImportTask>(page != null ? page : 1, size != null ? size : 20);
        return taskMapper.selectPage(p, wrapper).convert(this::toVO);
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
                    task.setSourceType(sourceType);
                    task.setSourcePath(path);
                    task.setBatchId(batchId);
                    task.setStatus("PENDING");
                    taskMapper.insert(task);

                    // 同步建立统一任务并回填 management_task_id
                    ManagementTaskResponse mgmtResp = createManagementTaskForImport(comic.getId(), null, null);
                    task.setManagementTaskId(mgmtResp.getId());
                    taskMapper.updateById(task);

                    // 写入 Outbox（同事务）
                    var evt = new ImportTaskCreatedEvent(
                            UUID.randomUUID(), Instant.now(), task.getId(), comic.getId(), sourceType, path);
                    outboxService.enqueue(evt, "comic.import", "task.created");

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
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        return toVO(t);
    }

    @Override
    public ImportStatusVO getTaskStatus(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        ImportStatusVO vo = new ImportStatusVO();
        vo.setTaskId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        return vo;
    }

    @Override
    @Transactional
    public void cancelTask(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        String status = t.getStatus();
        if ("SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "终态任务不可取消");
        }
        t.setStatus("CANCELLED");
        taskMapper.updateById(t);

        Long taskId = t.getId();
        Long comicId = t.getComicId();

        // 同步统一任务为 CANCELLED 真正终态（即使 item 已 RUNNING）
        if (t.getManagementTaskId() != null) {
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem("COMIC", comicId, TaskType.IMPORT);
            if (mgmtItem != null) {
                managementTaskService.updateItemStatus(mgmtItem.getId(), ManagementTaskStatus.CANCELLED,
                        "导入已取消", "IMPORT_TASK", taskId);
            }
        }

        // 写入 Outbox（同事务），由 relay 发布到 MQ
        var cancelEvent = new CancelTaskEvent(UUID.randomUUID(), Instant.now(), taskId, comicId);
        outboxService.enqueue(cancelEvent, "comic.task", "cancel.requested");

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
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "任务不存在");
        String status = t.getStatus();
        if (!"FAILED".equals(status) && !"CANCELLED".equals(status)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "仅 FAILED/CANCELLED 状态可重试");
        }

        Long comicId = t.getComicId();

        List<Long> chapterIds = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
        catalogCacheInvalidator.evict(comicId);

        t.setStatus("PENDING");
        t.setRetryCount(t.getRetryCount() + 1);
        t.setErrorMessage(null);
        taskMapper.updateById(t);

        // IMPORT_FAILED → IMPORTING，允许重新导入
        Comic comic = comicMapper.selectById(comicId);
        if (comic != null && comic.getStatus() == ComicStatus.IMPORT_FAILED) {
            ManagementStateMachine.validateComicTransition(comicStatusName(comic), "IMPORTING");
            comic.setStatus(ComicStatus.IMPORTING);
            comicMapper.updateById(comic);
        }

        Long taskId = t.getId();
        String sourceType = t.getSourceType();
        String sourcePath = t.getSourcePath();

        // 同步统一任务：终态统一任务重置回 QUEUED（attempt 递增，失败/取消 item 重新入队）
        if (t.getManagementTaskId() != null) {
            try {
                managementTaskService.retryTask(t.getManagementTaskId());
            } catch (BusinessException e) {
                log.warn("统一任务重试跳过（非终态）: managementTaskId={}, error={}",
                        t.getManagementTaskId(), e.getMessage());
            }
        }

        // 写入 Outbox（同事务），由 relay 发布到 MQ
        var retryEvent = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), taskId, comicId, sourceType, sourcePath);
        outboxService.enqueue(retryEvent, "comic.import", "task.created");

        // 非关键清理操作（不参与事务）
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Files.deleteIfExists(storageProperties.root("METADATA").resolve(taskId + ".json"));
                        } catch (Exception e) {
                            log.warn("Metadata cleanup failed for retry: taskId={}", taskId, e);
                        }
                        try {
                            redisTemplate.delete("import:cancel:" + taskId);
                        } catch (Exception e) {
                            log.warn("取消标记清理失败（非关键）: taskId={}, error={}", taskId, e.getMessage());
                        }
                    }
                });
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
        mgmtReq.setTaskType(com.comicatlas.common.enums.TaskType.IMPORT);
        mgmtReq.setOperation("导入漫画");
        mgmtReq.setTargetType("COMIC");
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("COMIC");
        target.setTargetId(comicId);
        target.setOperationType(com.comicatlas.common.enums.TaskType.IMPORT);
        mgmtReq.setTargets(List.of(target));
        return managementTaskService.createTask(mgmtReq, idempotencyKey, payload);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 兼容历史 "DIRECTORY" 值：语义等同 REGISTER（本地目录导入）。 */
    private static SourceType toSourceType(String sourceType) {
        if (sourceType == null) return null;
        if ("DIRECTORY".equals(sourceType)) return SourceType.REGISTER;
        try {
            return SourceType.valueOf(sourceType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String comicStatusName(Comic comic) {
        return comic.getStatus() == null ? null : comic.getStatus().name();
    }

    private ImportTaskVO toVO(ImportTask t) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setId(t.getId());
        vo.setComicId(t.getComicId());
        vo.setSourceRef(t.getSourceRef());
        vo.setSourceType(t.getSourceType());
        vo.setSourcePath(t.getSourcePath());
        vo.setBatchId(t.getBatchId());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        vo.setTotalPages(t.getTotalPages());
        vo.setDownloadedPages(t.getDownloadedPages());
        vo.setDownloadMethod(t.getDownloadMethod());
        vo.setDownloadSpeed(t.getDownloadSpeed());
        vo.setEtaSeconds(t.getEtaSeconds());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setRetryCount(t.getRetryCount());
        vo.setDurationMs(t.getDurationMs());
        vo.setStartTime(t.getStartTime());
        vo.setEndTime(t.getEndTime());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
