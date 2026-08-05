package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.ImportTaskFailedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ImportTaskMapper taskMapper;
    private final TransactionTemplate transactionTemplate;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ManagementTaskService managementTaskService;
    private final ApiStorageProperties storageProperties;

    /** 终态集合：到达这些状态后不可回退到非终态（含 CANCELLED 真正终态） */
    private static final Set<ImportTaskStatus> TERMINAL_STATUSES =
            EnumSet.of(ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);

    @RabbitListener(queues = "import.result.queue")
    @SuppressWarnings("unchecked")
    public void handleComicImported(ImportTaskCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String idempKey = "mq:event:" + event.eventId();
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        try {
            if (isEventProcessed(idempKey) || isImportTaskTerminal(taskId)) {
                log.info("事件已处理或任务已处终态，确认消息: eventId={}", event.eventId());
                markEventProcessed(idempKey);
                ack(channel, tag);
                return;
            }

            Map<String, Object> metadata = objectMapper.readValue(
                storageProperties.root("METADATA").resolve(taskId + ".json").toFile(),
                new TypeReference<Map<String, Object>>() {});

            ImportResult result = transactionTemplate.execute(status ->
                persistComicImported(event, metadata));
            markEventProcessed(idempKey);
            ack(channel, tag);

            log.info("ComicImported 完成: comicId={}, chapters={}, pages={}, skipped={}",
                comicId, result != null ? result.chapters() : 0,
                result != null ? result.pages() : 0,
                result != null && result.skipped());

        } catch (Exception e) {
            log.error("ComicImported 失败: taskId={}", taskId, e);
            reject(channel, tag);
        }
    }

    @SuppressWarnings("unchecked")
    private ImportResult persistComicImported(ImportTaskCompletedEvent event,
            Map<String, Object> metadata) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();

        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalStateException("导入任务不存在: " + taskId);
        }
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            return new ImportResult(0, 0, true);
        }

        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new IllegalStateException("漫画不存在: " + comicId);
        }

        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
        List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
        List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

        // metadata 版本：v2（默认，pages/imageName 全部 IMAGE）；v3（mediaItems/fileName，含 mediaType/视频字段）
        int metadataVersion = 2;
        Object verObj = metadata.get("version");
        if (verObj instanceof Number) {
            metadataVersion = ((Number) verObj).intValue();
        } else if (verObj != null) {
            try {
                metadataVersion = Integer.parseInt(verObj.toString());
            } catch (NumberFormatException e) { log.warn("解析 metadata version 失败: {}", verObj, e); }
        }

        // 1. UPDATE comic
        comic.setTitle((String) comicData.get("title"));
        comic.setTitleJpn((String) comicData.get("titleJpn"));
        comic.setAuthor((String) comicData.get("author"));
        comic.setCategory((String) comicData.get("category"));
        if (comicData.get("sourceGalleryId") != null) {
            comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
        }
        comic.setStoragePolicy("MANAGED");
        comic.setStatus(ComicStatus.READY);

        // 2. INSERT catalog（有则写入，无则跳过）
        Map<Integer, Long> catalogIdMap = insertCatalogs(catalogsData, comicId);

        // 3. INSERT chapters + pages
        int totalPages = 0;
        long totalSize = 0;
        if (chaptersData != null) {
            for (Map<String, Object> chData : chaptersData) {
                var result = insertChapter(chData, comicId, catalogIdMap, metadataVersion);
                totalPages += result.pages();
                totalSize += result.size();
            }
        }

        comic.setTotalPages(totalPages);
        if (totalSize > 0) {
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
        }
        comicMapper.updateById(comic);

        // 4. UPDATE import_task
        task.setStatus(ImportTaskStatus.SUCCESS);
        task.setEndTime(LocalDateTime.now());
        if (task.getStartTime() != null) {
            task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
        }
        taskMapper.updateById(task);
        catalogCacheInvalidator.evict(comicId);

        // 5. 标记管理任务项成功（若存在活跃导入任务）
        ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                "COMIC", comicId, TaskType.IMPORT);
        if (mgmtItem != null) {
            managementTaskService.updateItemStatus(
                    mgmtItem.getId(), ManagementTaskStatus.SUCCEEDED, null, "IMPORT_TASK", taskId);
        }

        return new ImportResult(chaptersData != null ? chaptersData.size() : 0, totalPages, false);
    }

    private Map<Integer, Long> insertCatalogs(List<Map<String, Object>> catalogsData, Long comicId) {
        Map<Integer, Long> idMap = new LinkedHashMap<>();
        if (catalogsData == null || catalogsData.isEmpty()) return idMap;

        int size = catalogsData.size();

        // 第一遍：INSERT 全部 catalog，建立 index → DB id 映射
        for (int i = 0; i < size; i++) {
            Map<String, Object> cd = catalogsData.get(i);
            Catalog cat = new Catalog();
            cat.setComicId(comicId);
            cat.setTitle((String) cd.get("title"));
            cat.setSortOrder((Integer) cd.getOrDefault("sortOrder", i));
            catalogMapper.insert(cat);
            idMap.put(i, cat.getId());
        }

        // 第二遍：恢复 parent_id
        Map<Long, Catalog> inserted = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Catalog cat = catalogMapper.selectById(idMap.get(i));
            if (cat == null) continue;
            inserted.put(idMap.get(i), cat);
        }

        for (int i = 0; i < size; i++) {
            Catalog cat = inserted.get(idMap.get(i));
            if (cat == null) continue;
            Map<String, Object> cd = catalogsData.get(i);
            Object pi = cd.get("parentIndex");
            if (pi != null) {
                int parentIdx = ((Number) pi).intValue();
                if (parentIdx < 0 || parentIdx >= size) {
                    throw new IllegalStateException(
                        "parentIndex 越界: index=" + parentIdx + ", catalogCount=" + size);
                }
                Long parentId = idMap.get(parentIdx);
                if (parentId == null) {
                    throw new IllegalStateException("parentIndex 对应 catalog 未找到: " + parentIdx);
                }
                Catalog parent = inserted.get(parentId);
                if (parent == null) {
                    throw new IllegalStateException("父 catalog 数据缺失: id=" + parentId);
                }
                cat.setParentId(parentId);
                catalogMapper.updateById(cat);
            }
        }

        return idMap;
    }

    private record ChapterResult(int pages, long size) {}

    private ChapterResult insertChapter(Map<String, Object> chData, Long comicId,
                                         Map<Integer, Long> catalogIdMap, int version) {
        Chapter chapter = new Chapter();
        chapter.setComicId(comicId);
        chapter.setTitle((String) chData.get("title"));
        chapter.setChapterNo((String) chData.get("chapterNo"));
        chapter.setSortOrder((Integer) chData.getOrDefault("sortOrder", 0));
        chapter.setGlobalOrder((Integer) chData.getOrDefault("globalOrder", 0));
        Object cid = chData.get("catalogIndex");
        if (cid != null) chapter.setCatalogId(catalogIdMap.get(((Number) cid).intValue()));

        // v2: pages + imageName; v3: mediaItems + fileName
        boolean isV3 = version >= 3;
        String itemsKey = isV3 ? "mediaItems" : "pages";
        String nameKey = isV3 ? "fileName" : "imageName";
        List<Map<String, Object>> itemList = (List<Map<String, Object>>) chData.get(itemsKey);
        chapter.setPageCount(itemList != null ? itemList.size() : 0);
        chapterMapper.insert(chapter);

        // 新布局迁移：如果 hqPath 使用 globalOrder 目录，重命名为 chapterId
        renameChapterDirIfNeeded(comicId, chapter.getId(), chapter.getGlobalOrder(), itemList);

        int pgCount = 0;
        long totalSize = 0;
        if (itemList != null) {
            for (Map<String, Object> md : itemList) {
                Media media = new Media();
                media.setChapterId(chapter.getId());
                media.setPageNumber(((Number) md.get("pageNumber")).intValue());
                media.setHqRoot("HQ");
                // hqPath: 优先使用 metadata 中的值，fallback 构造旧格式路径
                String hqPath = (String) md.get("hqPath");
                if (hqPath == null || hqPath.isBlank()) {
                    hqPath = comicId + "/" + chapter.getGlobalOrder() + "/" + md.get(nameKey);
                }
                // 新布局：将 globalOrder 目录替换为 chapterId
                hqPath = normalizeToChapterIdLayout(hqPath, comicId, chapter.getId(), chapter.getGlobalOrder());
                media.setHqPath(hqPath);
                Object hqRaw = md.get("hqStatus");
                HqStatus hqStatus = HqStatus.READY;
                if (hqRaw != null) {
                    try {
                        hqStatus = HqStatus.valueOf((String) hqRaw);
                    } catch (IllegalArgumentException e) {
                        log.warn("metadata 中未知 hqStatus: {}，回退 READY", hqRaw);
                    }
                }
                media.setHqStatus(hqStatus);
                media.setLqStatus(LqStatus.NOT_GENERATED);
                if (md.get("fileSize") != null) media.setFileSize(((Number) md.get("fileSize")).longValue());
                if (md.get("width") != null) media.setWidth(((Number) md.get("width")).intValue());
                if (md.get("height") != null) media.setHeight(((Number) md.get("height")).intValue());

                // mediaType: v2 强制 IMAGE；v3 读取 metadata 中的 mediaType
                String mediaType = isV3 ? (String) md.get("mediaType") : "IMAGE";
                if (mediaType == null || mediaType.isBlank()) {
                    mediaType = "IMAGE";
                }
                media.setMediaType(mediaType);

                // VIDEO 专属字段
                if ("VIDEO".equalsIgnoreCase(mediaType)) {
                    if (md.get("duration") != null) {
                        media.setDuration(toBigDecimal(md.get("duration")));
                    }
                    if (md.get("container") != null) {
                        media.setContainer((String) md.get("container"));
                    }
                    if (md.get("videoCodec") != null) {
                        media.setVideoCodec((String) md.get("videoCodec"));
                    }
                    if (md.get("audioCodec") != null) {
                        media.setAudioCodec((String) md.get("audioCodec"));
                    }
                }

                mediaMapper.insert(media);
                totalSize += media.getFileSize() != null ? media.getFileSize() : 0;
                pgCount++;
            }
        }
        return new ChapterResult(pgCount, totalSize);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @RabbitListener(queues = "task.status.queue")
    public void handleTaskStatusChanged(TaskStatusChangedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        try {
            transactionTemplate.executeWithoutResult(status -> persistTaskStatusChanged(event));
            ack(channel, tag);
        } catch (Exception e) {
            log.error("TaskStatusChanged 失败", e);
            reject(channel, tag);
        }
    }

    private void persistTaskStatusChanged(TaskStatusChangedEvent event) {
        Long taskId = event.taskId();
        String newStatus = event.status();
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) return;

        ImportTaskStatus currentStatus = task.getStatus();
        ImportTaskStatus mappedStatus = parseStatus(newStatus);
        if (TERMINAL_STATUSES.contains(currentStatus)
                && (mappedStatus == null || !mappedStatus.isTerminal())) {
            log.warn("状态机拒绝非终态写入: taskId={}, current={}, attempted={}", taskId, currentStatus, newStatus);
            return;
        }

        if (mappedStatus != null) {
            task.setStatus(mappedStatus);
        }
        if ("DOWNLOADING".equals(newStatus) && task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        task.setProgress(event.progress());
        if (event.speedBytesPerSec() > 0) task.setDownloadSpeed(event.speedBytesPerSec());
        if (event.etaSeconds() > 0) task.setEtaSeconds(event.etaSeconds());
        if (event.downloadMethod() != null) task.setDownloadMethod(event.downloadMethod());
        taskMapper.updateById(task);

        // 阶段状态（DOWNLOADING/EXTRACTING/PARSING）同步到统一任务 stage 列（TaskStage 枚举）
        if (task.getManagementTaskId() != null) {
            com.comicatlas.common.enums.TaskStage stage =
                    com.comicatlas.common.enums.TaskStage.fromStatus(newStatus);
            if (stage != null) {
                managementTaskService.updateStage(task.getManagementTaskId(), stage, event.progress());
            }
        }

        // QA 修复注记（task-21）：Worker 导入失败只发 TaskStatusChangedEvent(FAILED)，
        // 不发 ImportTaskFailedEvent，导致统一管理任务 item 滞留 RUNNING（导入任务已
        // FAILED 但 management_task 仍 RUNNING）→ retryTask 校验非终态抛异常并把外层
        // 事务标记 rollback-only → 重试 500。此处把 FAILED/CANCELLED 联动到管理任务 item。
        if (task.getManagementTaskId() != null
                && ("FAILED".equals(newStatus) || "CANCELLED".equals(newStatus))) {
            ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                    "COMIC", task.getComicId(), TaskType.IMPORT);
            if (mgmtItem != null) {
                ManagementTaskStatus mgmtStatus = "CANCELLED".equals(newStatus)
                        ? ManagementTaskStatus.CANCELLED
                        : ManagementTaskStatus.FAILED;
                managementTaskService.updateItemStatus(
                        mgmtItem.getId(), mgmtStatus, null, "IMPORT_TASK", task.getId());
            }
        }
    }

    @RabbitListener(queues = "import.failed.queue")
    public void handleImportTaskFailed(ImportTaskFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        log.warn("ImportTaskFailed: taskId={}, errorCode={}, message={}",
                taskId, event.errorCode(), event.errorMessage());

        try {
            transactionTemplate.executeWithoutResult(status -> {
                ImportTask task = taskMapper.selectById(taskId);
                if (task == null || TERMINAL_STATUSES.contains(task.getStatus())) {
                    return;
                }
                task.setStatus(ImportTaskStatus.FAILED);
                task.setEndTime(LocalDateTime.now());
                if (event.errorCode() != null) {
                    task.setErrorMessage(event.errorCode() + ": " + event.errorMessage());
                } else if (event.errorMessage() != null) {
                    task.setErrorMessage(event.errorMessage());
                }
                taskMapper.updateById(task);
                markImportFailed(task);
            });
            ack(channel, tag);
        } catch (Exception e) {
            log.error("ImportTaskFailed 处理失败: taskId={}", taskId, e);
            reject(channel, tag);
        }
    }

    /**
     * 导入失败：comic → IMPORT_FAILED（可重试），并标记管理任务项失败。
     */
    private void markImportFailed(ImportTask task) {
        Comic comic = comicMapper.selectById(task.getComicId());
        if (comic == null) {
            return;
        }
        if (comic.getStatus() == ComicStatus.IMPORTING) {
            ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "IMPORT_FAILED");
            comic.setStatus(ComicStatus.IMPORT_FAILED);
            comicMapper.updateById(comic);
        }
        ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                "COMIC", comic.getId(), TaskType.IMPORT);
        if (mgmtItem != null) {
            managementTaskService.updateItemStatus(
                    mgmtItem.getId(), ManagementTaskStatus.FAILED,
                    task.getErrorMessage(), "IMPORT_TASK", task.getId());
        }
    }

    private boolean isEventProcessed(String idempKey) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(idempKey));
        } catch (Exception e) {
            log.warn("幂等标记读取失败，降级使用 DB 状态判断: key={}", idempKey, e);
            return false;
        }
    }

    private boolean isImportTaskTerminal(Long taskId) {
        ImportTask task = taskMapper.selectById(taskId);
        return task != null && TERMINAL_STATUSES.contains(task.getStatus());
    }

    /** 将事件状态字符串映射为 ImportTaskStatus；阶段值（DOWNLOADING/EXTRACTING）返回 null。 */
    private static ImportTaskStatus parseStatus(String status) {
        if (status == null) return null;
        try {
            return ImportTaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 新布局迁移：如果章节目录使用 globalOrder 命名，重命名为 chapterId。
     * 仅在目录存在且使用 globalOrder 模式时执行重命名。
     */
    private void renameChapterDirIfNeeded(Long comicId, Long chapterId, int globalOrder,
                                          List<Map<String, Object>> itemList) {
        if (itemList == null || itemList.isEmpty()) return;
        String firstHqPath = (String) itemList.get(0).get("hqPath");
        if (firstHqPath == null || firstHqPath.isBlank()) return;

        // 仅当 hqPath 包含 "/globalOrder/" 模式时执行迁移
        String oldDirPattern = comicId + "/" + globalOrder + "/";
        if (!firstHqPath.contains(oldDirPattern)) return;

        Path hqRoot = storageProperties.root("HQ").getPath();
        Path oldDir = hqRoot.resolve(comicId.toString()).resolve(String.valueOf(globalOrder));
        Path newDir = hqRoot.resolve(comicId.toString()).resolve(String.valueOf(chapterId));

        if (Files.exists(oldDir) && !Files.exists(newDir)) {
            try {
                Files.createDirectories(newDir.getParent());
                Files.move(oldDir, newDir);
                log.info("章节目录重命名: {} -> {}", oldDir, newDir);
            } catch (IOException e) {
                log.warn("章节目录重命名失败（非致命）: old={}, new={}, error={}",
                        oldDir, newDir, e.getMessage());
            }
        }
    }

    /**
     * 将 hqPath 中的 globalOrder 目录替换为 chapterId 目录。
     * 如果路径中不包含 globalOrder 模式，则原样返回（旧布局兼容）。
     */
    private String normalizeToChapterIdLayout(String hqPath, Long comicId, Long chapterId, int globalOrder) {
        String oldDir = comicId + "/" + globalOrder + "/";
        if (hqPath.contains(oldDir)) {
            return hqPath.replace(oldDir, comicId + "/" + chapterId + "/");
        }
        return hqPath;
    }

    private void markEventProcessed(String idempKey) {
        try {
            redisTemplate.opsForValue().set(idempKey, "1", Duration.ofDays(1));
        } catch (Exception e) {
            log.warn("幂等标记写入失败: key={}", idempKey, e);
        }
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("消息 ack 失败: tag={}", tag, e);
        }
    }

    private void reject(Channel channel, long tag) {
        try {
            channel.basicReject(tag, false);
        } catch (Exception e) {
            log.error("消息 reject 失败: tag={}", tag, e);
        }
    }

    private record ImportResult(int chapters, int pages, boolean skipped) {}
}
