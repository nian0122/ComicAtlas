package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final ImportTaskMapper taskMapper;
    private final TransactionTemplate transactionTemplate;

    /** 终态集合：到达这些状态后不可回退到非终态 */
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED");

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @RabbitListener(queues = "import.result.queue")
    @SuppressWarnings("unchecked")
    public void handleComicImported(ImportTaskCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String idempKey = "mq:event:" + event.eventId();
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        try {
            if (isEventProcessed(idempKey) || isImportTaskSucceeded(taskId)) {
                log.info("事件已处理，确认消息: eventId={}", event.eventId());
                markEventProcessed(idempKey);
                ack(channel, tag);
                return;
            }

            Map<String, Object> metadata = objectMapper.readValue(
                new File(mangaRoot + "/metadata/" + taskId + ".json"),
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
        if ("SUCCESS".equals(task.getStatus())) {
            return new ImportResult(0, 0, true);
        }

        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new IllegalStateException("漫画不存在: " + comicId);
        }

        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
        List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
        List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

        // 1. UPDATE comic
        comic.setTitle((String) comicData.get("title"));
        comic.setTitleJpn((String) comicData.get("titleJpn"));
        comic.setAuthor((String) comicData.get("author"));
        comic.setCategory((String) comicData.get("category"));
        if (comicData.get("sourceGalleryId") != null) {
            comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
        }
        comic.setStoragePolicy("MANAGED");
        comic.setStatus("READY");

        // 2. INSERT catalog（有则写入，无则跳过）
        Map<Integer, Long> catalogIdMap = insertCatalogs(catalogsData, comicId);

        // 3. INSERT chapters + pages
        int totalPages = 0;
        long totalSize = 0;
        if (chaptersData != null) {
            for (Map<String, Object> chData : chaptersData) {
                var result = insertChapter(chData, comicId, catalogIdMap);
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
        task.setStatus("SUCCESS");
        task.setEndTime(LocalDateTime.now());
        if (task.getStartTime() != null) {
            task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
        }
        taskMapper.updateById(task);

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

        // 第二遍：恢复 parent_id / level / path
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
            if (pi == null) {
                cat.setLevel(0);
                cat.setPath(cat.getTitle());
            } else {
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
                cat.setLevel(parent.getLevel() + 1);
                cat.setPath(parent.getPath() + "/" + cat.getTitle());
            }
            catalogMapper.updateById(cat);
        }

        return idMap;
    }

    private record ChapterResult(int pages, long size) {}

    private ChapterResult insertChapter(Map<String, Object> chData, Long comicId,
                                         Map<Integer, Long> catalogIdMap) {
        Chapter chapter = new Chapter();
        chapter.setComicId(comicId);
        chapter.setTitle((String) chData.get("title"));
        chapter.setChapterNo((String) chData.get("chapterNo"));
        chapter.setSortOrder((Integer) chData.getOrDefault("sortOrder", 0));
        chapter.setGlobalOrder((Integer) chData.getOrDefault("globalOrder", 0));
        Object cid = chData.get("catalogIndex");
        if (cid != null) chapter.setCatalogId(catalogIdMap.get(((Number) cid).intValue()));

        List<Map<String, Object>> pageList = (List<Map<String, Object>>) chData.get("pages");
        chapter.setPageCount(pageList != null ? pageList.size() : 0);
        chapterMapper.insert(chapter);

        int pgCount = 0;
        long totalSize = 0;
        if (pageList != null) {
            for (Map<String, Object> pd : pageList) {
                Page page = new Page();
                page.setChapterId(chapter.getId());
                page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                page.setHqRoot("HQ");
                page.setHqPath((String) pd.getOrDefault("hqPath",
                    comicId + "/" + chapter.getGlobalOrder() + "/" + pd.get("imageName")));
                page.setHqStatus(pd.get("hqStatus") != null ? (String) pd.get("hqStatus") : "READY");
                page.setLqStatus("NOT_GENERATED");
                if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                pageMapper.insert(page);
                totalSize += page.getFileSize() != null ? page.getFileSize() : 0;
                pgCount++;
            }
        }
        return new ChapterResult(pgCount, totalSize);
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

        String currentStatus = task.getStatus();
        if (TERMINAL_STATUSES.contains(currentStatus) && !TERMINAL_STATUSES.contains(newStatus)) {
            log.warn("状态机拒绝非终态写入: taskId={}, current={}, attempted={}", taskId, currentStatus, newStatus);
            return;
        }

        task.setStatus(newStatus);
        if ("DOWNLOADING".equals(newStatus) && task.getStartTime() == null) {
            task.setStartTime(LocalDateTime.now());
        }
        task.setProgress(event.progress());
        if (event.speedBytesPerSec() > 0) task.setDownloadSpeed(event.speedBytesPerSec());
        if (event.etaSeconds() > 0) task.setEtaSeconds(event.etaSeconds());
        if (event.downloadMethod() != null) task.setDownloadMethod(event.downloadMethod());
        taskMapper.updateById(task);
    }

    @RabbitListener(queues = "import.failed.queue")
    public void handleImportTaskFailed(ImportTaskFailedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        log.warn("ImportTaskFailed: taskId={}, errorCode={}, message={}",
                taskId, event.errorCode(), event.errorMessage());

        try {
            ImportTask task = taskMapper.selectById(taskId);
            if (task == null) {
                ack(channel, tag);
                return;
            }
            if (TERMINAL_STATUSES.contains(task.getStatus())) {
                log.info("任务已处终态，跳过失败事件: taskId={}, status={}", taskId, task.getStatus());
                ack(channel, tag);
                return;
            }
            task.setStatus("FAILED");
            task.setEndTime(LocalDateTime.now());
            if (event.errorCode() != null) {
                task.setErrorMessage(event.errorCode() + ": " + event.errorMessage());
            } else if (event.errorMessage() != null) {
                task.setErrorMessage(event.errorMessage());
            }
            taskMapper.updateById(task);
            ack(channel, tag);
        } catch (Exception e) {
            log.error("ImportTaskFailed 处理失败: taskId={}", taskId, e);
            reject(channel, tag);
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

    private boolean isImportTaskSucceeded(Long taskId) {
        ImportTask task = taskMapper.selectById(taskId);
        return task != null && "SUCCESS".equals(task.getStatus());
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
